import type { APIGatewayProxyEventV2WithJWTAuthorizer, APIGatewayProxyResultV2 } from 'aws-lambda';
import { randomUUID } from 'node:crypto';
import { DeleteCommand, GetCommand, PutCommand, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { ZodError } from 'zod';
import { ddb, TABLE_NAME, todoKey, userKey } from '../../shared/dynamo';
import { createTodoSchema, updateTodoSchema, type CreateTodoInput } from '../../shared/todo-schema';
import { buildTodoItem } from '../../shared/todo-item';
import { nextOccurrenceDueDate, shiftReminder } from '../../shared/recurrence';
import type { TodoItem } from '../../shared/types';

/**
 * Todo CRUD — GET/POST/PATCH/DELETE /todos[/{id}]. One Lambda, routed
 * internally on API Gateway's routeKey, per the "one function per concern"
 * rule in ../../docs/architecture.md §05.
 *
 * Every item is scoped to the caller's Cognito `sub` — there is no
 * cross-user access path, deliberately, even though this is a single-user
 * app today (see docs/decisions/0002-cloud-first-source-of-truth.md).
 */
export const handler = async (
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
): Promise<APIGatewayProxyResultV2> => {
  const userId = event.requestContext.authorizer.jwt.claims.sub as string;
  const todoId = event.pathParameters?.id;

  try {
    switch (event.routeKey) {
      case 'GET /todos':
        return await listTodos(userId, event.queryStringParameters?.status);
      case 'GET /todos/{id}':
        return todoId ? await getTodo(userId, todoId) : badRequest('missing id');
      case 'POST /todos':
        return await createTodo(userId, parseBody(event));
      case 'PATCH /todos/{id}':
        return todoId ? await updateTodo(userId, todoId, parseBody(event)) : badRequest('missing id');
      case 'DELETE /todos/{id}':
        return todoId ? await deleteTodo(userId, todoId) : badRequest('missing id');
      default:
        return json(404, { error: `no route for ${event.routeKey}` });
    }
  } catch (err) {
    if (err instanceof ZodError) {
      return json(400, { error: 'validation failed', details: err.issues });
    }
    console.error('todo-api error', err);
    return json(500, { error: 'internal error' });
  }
};

// ---------- route handlers ----------

async function listTodos(userId: string, status?: string): Promise<APIGatewayProxyResultV2> {
  const result = await ddb.send(new QueryCommand({
    TableName: TABLE_NAME,
    KeyConditionExpression: 'PK = :pk AND begins_with(SK, :prefix)',
    ExpressionAttributeValues: { ':pk': userKey(userId), ':prefix': 'TODO#' },
  }));
  let items = (result.Items ?? []) as TodoItem[];
  if (status) items = items.filter((t) => t.status === status);
  return json(200, { items: items.map(stripKeys) });
}

async function getTodo(userId: string, todoId: string): Promise<APIGatewayProxyResultV2> {
  const result = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: todoKey(userId, todoId) }));
  if (!result.Item) return json(404, { error: 'not found' });
  return json(200, stripKeys(result.Item as TodoItem));
}

async function createTodo(userId: string, body: unknown): Promise<APIGatewayProxyResultV2> {
  const input = createTodoSchema.parse(body);
  const id = randomUUID();
  const item = buildTodoItem(userId, id, input);
  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: item }));
  return json(201, stripKeys(item));
}

async function updateTodo(userId: string, todoId: string, body: unknown): Promise<APIGatewayProxyResultV2> {
  const existingResult = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: todoKey(userId, todoId) }));
  if (!existingResult.Item) return json(404, { error: 'not found' });
  const existing = existingResult.Item as TodoItem;

  const patch = updateTodoSchema.parse(body);
  const updated = buildTodoItem(userId, todoId, { ...toInput(existing), ...patch }, existing.createdAt);

  const completingNow = existing.status !== 'done' && updated.status === 'done';
  const reopening = existing.status === 'done' && updated.status !== 'done';
  // completedAt is server-derived, not part of the client's patch (see
  // shared/types.ts's doc comment) — buildTodoItem always sets it to null,
  // so it's fixed up here based on the actual status transition: stamped
  // on completion, cleared on reopening, left alone (carried forward from
  // `existing`) for any other edit to an already-done or already-open todo.
  updated.completedAt = completingNow ? updated.updatedAt : reopening ? null : (existing.completedAt ?? null);

  let materialized: TodoItem | undefined;
  if (completingNow && existing.recurrenceRule && existing.dueDate) {
    materialized = materializeNextOccurrence(userId, existing);
    if (materialized) {
      await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: materialized }));
    }
  }

  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: updated }));
  return json(200, {
    ...stripKeys(updated),
    ...(materialized ? { nextOccurrence: stripKeys(materialized) } : {}),
  });
}

async function deleteTodo(userId: string, todoId: string): Promise<APIGatewayProxyResultV2> {
  await ddb.send(new DeleteCommand({ TableName: TABLE_NAME, Key: todoKey(userId, todoId) }));
  return json(200, { deleted: true });
}

// ---------- helpers ----------

function parseBody(event: APIGatewayProxyEventV2WithJWTAuthorizer): unknown {
  const raw = event.body ?? '{}';
  const text = event.isBase64Encoded ? Buffer.from(raw, 'base64').toString('utf8') : raw;
  return text.trim().length ? JSON.parse(text) : {};
}

function toInput(item: TodoItem): CreateTodoInput {
  return {
    title: item.title,
    notes: item.notes,
    priority: item.priority,
    dueDate: item.dueDate,
    reminderAt: item.reminderAt,
    recurrenceRule: item.recurrenceRule,
    project: item.project,
    tags: item.tags,
    source: item.source,
  };
}

function materializeNextOccurrence(userId: string, existing: TodoItem): TodoItem | undefined {
  if (!existing.recurrenceRule || !existing.dueDate) return undefined;
  const nextDue = nextOccurrenceDueDate(existing.dueDate, existing.recurrenceRule);
  if (!nextDue) return undefined;

  const newId = randomUUID();
  return buildTodoItem(userId, newId, {
    title: existing.title,
    notes: existing.notes,
    priority: existing.priority,
    dueDate: nextDue,
    reminderAt: shiftReminder(existing.dueDate, existing.reminderAt, nextDue),
    recurrenceRule: existing.recurrenceRule,
    project: existing.project,
    tags: existing.tags,
    source: existing.source,
    status: 'open',
  });
}

/** Never expose the raw table keys (PK/SK/GSI1*) or entityType over the API. */
function stripKeys(item: TodoItem): Record<string, unknown> {
  const { PK, SK, GSI1PK, GSI1SK, entityType, ...rest } = item;
  void PK; void SK; void GSI1PK; void GSI1SK; void entityType;
  return rest;
}

function json(statusCode: number, body: unknown): APIGatewayProxyResultV2 {
  return {
    statusCode,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  };
}

function badRequest(error: string): APIGatewayProxyResultV2 {
  return json(400, { error });
}

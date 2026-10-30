import type { APIGatewayProxyEventV2WithJWTAuthorizer, APIGatewayProxyResultV2 } from 'aws-lambda';
import { randomUUID } from 'node:crypto';
import { DeleteCommand, GetCommand, PutCommand, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { ZodError } from 'zod';
import { ddb, TABLE_NAME, goalKey, userKey } from '../../shared/dynamo';
import { createGoalSchema, updateGoalSchema, type CreateGoalInput } from '../../shared/goal-schema';
import { buildGoalItem } from '../../shared/goal-item';
import type { GoalItem } from '../../shared/types';

/**
 * Goal CRUD — GET/POST/PATCH/DELETE /goals[/{id}]. Same one-Lambda,
 * routed-on-routeKey shape as lambdas/todo-api/index.ts (the "one
 * function per concern" rule in ../../docs/architecture.md §05) — goals
 * are simple enough, and different enough from a Todo (no recurrence, no
 * reminder GSI1 keys, no completedAt derivation), that folding them into
 * todo-api would mean threading goal-only branches through logic that's
 * otherwise entirely about todos.
 *
 * Every item is scoped to the caller's Cognito `sub`, same as every other
 * route in this app — no cross-user access path.
 */
export const handler = async (
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
): Promise<APIGatewayProxyResultV2> => {
  const userId = event.requestContext.authorizer.jwt.claims.sub as string;
  const goalId = event.pathParameters?.id;

  try {
    switch (event.routeKey) {
      case 'GET /goals':
        return await listGoals(userId, event.queryStringParameters?.status);
      case 'GET /goals/{id}':
        return goalId ? await getGoal(userId, goalId) : badRequest('missing id');
      case 'POST /goals':
        return await createGoal(userId, parseBody(event));
      case 'PATCH /goals/{id}':
        return goalId ? await updateGoal(userId, goalId, parseBody(event)) : badRequest('missing id');
      case 'DELETE /goals/{id}':
        return goalId ? await deleteGoal(userId, goalId) : badRequest('missing id');
      default:
        return json(404, { error: `no route for ${event.routeKey}` });
    }
  } catch (err) {
    if (err instanceof ZodError) {
      return json(400, { error: 'validation failed', details: err.issues });
    }
    console.error('goal-api error', err);
    return json(500, { error: 'internal error' });
  }
};

// ---------- route handlers ----------

async function listGoals(userId: string, status?: string): Promise<APIGatewayProxyResultV2> {
  const result = await ddb.send(new QueryCommand({
    TableName: TABLE_NAME,
    KeyConditionExpression: 'PK = :pk AND begins_with(SK, :prefix)',
    ExpressionAttributeValues: { ':pk': userKey(userId), ':prefix': 'GOAL#' },
  }));
  let items = (result.Items ?? []) as GoalItem[];
  if (status) items = items.filter((g) => g.status === status);
  return json(200, { items: items.map(stripKeys) });
}

async function getGoal(userId: string, goalId: string): Promise<APIGatewayProxyResultV2> {
  const result = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: goalKey(userId, goalId) }));
  if (!result.Item) return json(404, { error: 'not found' });
  return json(200, stripKeys(result.Item as GoalItem));
}

async function createGoal(userId: string, body: unknown): Promise<APIGatewayProxyResultV2> {
  const input = createGoalSchema.parse(body);
  const id = randomUUID();
  const item = buildGoalItem(userId, id, input);
  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: item }));
  return json(201, stripKeys(item));
}

async function updateGoal(userId: string, goalId: string, body: unknown): Promise<APIGatewayProxyResultV2> {
  const existingResult = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: goalKey(userId, goalId) }));
  if (!existingResult.Item) return json(404, { error: 'not found' });
  const existing = existingResult.Item as GoalItem;

  const patch = updateGoalSchema.parse(body);
  const updated = buildGoalItem(userId, goalId, { ...toInput(existing), ...patch }, existing.createdAt);

  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: updated }));
  return json(200, stripKeys(updated));
}

async function deleteGoal(userId: string, goalId: string): Promise<APIGatewayProxyResultV2> {
  await ddb.send(new DeleteCommand({ TableName: TABLE_NAME, Key: goalKey(userId, goalId) }));
  return json(200, { deleted: true });
}

// ---------- helpers ----------

function parseBody(event: APIGatewayProxyEventV2WithJWTAuthorizer): unknown {
  const raw = event.body ?? '{}';
  const text = event.isBase64Encoded ? Buffer.from(raw, 'base64').toString('utf8') : raw;
  return text.trim().length ? JSON.parse(text) : {};
}

function toInput(item: GoalItem): CreateGoalInput {
  return {
    title: item.title,
    notes: item.notes,
    targetDate: item.targetDate,
  };
}

/** Never expose the raw table keys (PK/SK) or entityType over the API. */
function stripKeys(item: GoalItem): Record<string, unknown> {
  const { PK, SK, entityType, ...rest } = item;
  void PK; void SK; void entityType;
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

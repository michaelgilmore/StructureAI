import type { APIGatewayProxyEventV2WithJWTAuthorizer, APIGatewayProxyResultV2 } from 'aws-lambda';
import { randomUUID } from 'node:crypto';
import { DeleteCommand, GetCommand, PutCommand, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { ZodError } from 'zod';
import { ddb, TABLE_NAME, routineKey, userKey } from '../../shared/dynamo';
import { createRoutineSchema, updateRoutineSchema, type CreateRoutineInput } from '../../shared/routine-schema';
import { buildRoutineItem } from '../../shared/routine-item';
import type { RoutineItem } from '../../shared/types';

/**
 * Routine CRUD — GET/POST/PATCH/DELETE /routines[/{id}]. Mirrors
 * lambdas/goal-api/index.ts exactly (see that file's doc comment for the
 * one-Lambda-per-concern reasoning) — a Routine is just as simple as a
 * Goal structurally, it just adds targetCount/period instead of
 * targetDate.
 *
 * Every item is scoped to the caller's Cognito `sub`, same as every other
 * route in this app — no cross-user access path.
 */
export const handler = async (
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
): Promise<APIGatewayProxyResultV2> => {
  const userId = event.requestContext.authorizer.jwt.claims.sub as string;
  const routineId = event.pathParameters?.id;

  try {
    switch (event.routeKey) {
      case 'GET /routines':
        return await listRoutines(userId, event.queryStringParameters?.status);
      case 'GET /routines/{id}':
        return routineId ? await getRoutine(userId, routineId) : badRequest('missing id');
      case 'POST /routines':
        return await createRoutine(userId, parseBody(event));
      case 'PATCH /routines/{id}':
        return routineId ? await updateRoutine(userId, routineId, parseBody(event)) : badRequest('missing id');
      case 'DELETE /routines/{id}':
        return routineId ? await deleteRoutine(userId, routineId) : badRequest('missing id');
      default:
        return json(404, { error: `no route for ${event.routeKey}` });
    }
  } catch (err) {
    if (err instanceof ZodError) {
      return json(400, { error: 'validation failed', details: err.issues });
    }
    console.error('routine-api error', err);
    return json(500, { error: 'internal error' });
  }
};

// ---------- route handlers ----------

async function listRoutines(userId: string, status?: string): Promise<APIGatewayProxyResultV2> {
  const result = await ddb.send(new QueryCommand({
    TableName: TABLE_NAME,
    KeyConditionExpression: 'PK = :pk AND begins_with(SK, :prefix)',
    ExpressionAttributeValues: { ':pk': userKey(userId), ':prefix': 'ROUTINE#' },
  }));
  let items = (result.Items ?? []) as RoutineItem[];
  if (status) items = items.filter((r) => r.status === status);
  return json(200, { items: items.map(stripKeys) });
}

async function getRoutine(userId: string, routineId: string): Promise<APIGatewayProxyResultV2> {
  const result = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: routineKey(userId, routineId) }));
  if (!result.Item) return json(404, { error: 'not found' });
  return json(200, stripKeys(result.Item as RoutineItem));
}

async function createRoutine(userId: string, body: unknown): Promise<APIGatewayProxyResultV2> {
  const input = createRoutineSchema.parse(body);
  const id = randomUUID();
  const item = buildRoutineItem(userId, id, input);
  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: item }));
  return json(201, stripKeys(item));
}

async function updateRoutine(userId: string, routineId: string, body: unknown): Promise<APIGatewayProxyResultV2> {
  const existingResult = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: routineKey(userId, routineId) }));
  if (!existingResult.Item) return json(404, { error: 'not found' });
  const existing = existingResult.Item as RoutineItem;

  const patch = updateRoutineSchema.parse(body);
  const updated = buildRoutineItem(userId, routineId, { ...toInput(existing), ...patch }, existing.createdAt);

  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: updated }));
  return json(200, stripKeys(updated));
}

async function deleteRoutine(userId: string, routineId: string): Promise<APIGatewayProxyResultV2> {
  await ddb.send(new DeleteCommand({ TableName: TABLE_NAME, Key: routineKey(userId, routineId) }));
  return json(200, { deleted: true });
}

// ---------- helpers ----------

function parseBody(event: APIGatewayProxyEventV2WithJWTAuthorizer): unknown {
  const raw = event.body ?? '{}';
  const text = event.isBase64Encoded ? Buffer.from(raw, 'base64').toString('utf8') : raw;
  return text.trim().length ? JSON.parse(text) : {};
}

function toInput(item: RoutineItem): CreateRoutineInput {
  return {
    title: item.title,
    notes: item.notes,
    targetCount: item.targetCount,
    period: item.period,
  };
}

/** Never expose the raw table keys (PK/SK) or entityType over the API. */
function stripKeys(item: RoutineItem): Record<string, unknown> {
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

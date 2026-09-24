import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient } from '@aws-sdk/lib-dynamodb';

const client = new DynamoDBClient({});

/** Shared document client — one per Lambda execution environment, reused across invocations. */
export const ddb = DynamoDBDocumentClient.from(client, {
  marshallOptions: { removeUndefinedValues: true },
});

export const TABLE_NAME = process.env.TABLE_NAME ?? '';

export function userKey(userId: string): string {
  return `USER#${userId}`;
}

export function todoKey(userId: string, todoId: string): { PK: string; SK: string } {
  return { PK: userKey(userId), SK: `TODO#${todoId}` };
}

/** One row per user — this app is single-device today (see lambdas/device-token). */
export function deviceKey(userId: string): { PK: string; SK: string } {
  return { PK: userKey(userId), SK: 'DEVICE' };
}

// --- Phase 2 (AI) — see shared/types.ts and lambdas/ai-orchestrator ---

export function planKey(userId: string, scope: string, periodStart: string): { PK: string; SK: string } {
  return { PK: userKey(userId), SK: `PLAN#${scope}#${periodStart}` };
}

export function convoKey(userId: string, convoId: string): { PK: string; SK: string } {
  return { PK: userKey(userId), SK: `CONVO#${convoId}` };
}

export function aiRequestKey(userId: string, reqId: string): { PK: string; SK: string } {
  return { PK: userKey(userId), SK: `REQ#${reqId}` };
}

// --- Phase 3 (Telegram bridge) — see shared/types.ts and lambdas/telegram-webhook ---

/**
 * One row per user, overwritten in place — see HomeStatusItem's doc
 * comment. Return type pins SK to the literal `'HOME#status'` (not plain
 * `string`) so `{ ...homeStatusKey(userId), ... }` type-checks directly
 * against `HomeStatusItem`, whose own SK field is that same literal.
 */
export function homeStatusKey(userId: string): { PK: string; SK: 'HOME#status' } {
  return { PK: userKey(userId), SK: 'HOME#status' };
}

/**
 * Temporary reassembly buffer for a chunked Telegram frame (see
 * shared/telegram.ts) — one item per (userId, frame type, reqId), deleted
 * once all chunks arrive. `reqId` is `undefined` for a heartbeat frame,
 * which isn't tied to any particular AI request; `'_'` stands in for it so
 * the SK is still well-formed (Dynamo SKs can't be genuinely empty here).
 */
export function chunkBufferKey(userId: string, type: string, reqId: string | undefined): { PK: string; SK: string } {
  return { PK: userKey(userId), SK: `CHUNKBUF#${type}#${reqId ?? '_'}` };
}

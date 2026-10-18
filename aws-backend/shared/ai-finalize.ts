import { randomUUID } from 'node:crypto';
import { PutCommand } from '@aws-sdk/lib-dynamodb';
import { ddb, TABLE_NAME, convoKey, planKey } from './dynamo';
import { chatToolOutputSchema, planToolOutputSchema, type AiCreateTodoInput, type DueDateChangeSuggestion } from './ai-schema';
import { buildTodoItem } from './todo-item';
import type { AiMode, ChatSubjectType, ConversationMessage, ConversationRecord, PlanRecord, PlanScope } from './types';

/**
 * The "turn a validated model reply into a persisted Plan/Conversation
 * record" half of lambdas/ai-orchestrator's logic — pulled out to
 * shared/ so lambdas/telegram-webhook can reuse it verbatim for Home-mode
 * replies (../docs/architecture.md §04) instead of re-implementing the
 * same persistence rules a second time. The Bedrock-calling half
 * (lambdas/ai-orchestrator/bedrock.ts) stays where it is — telegram-webhook
 * never calls Bedrock, so there's nothing there for it to share.
 *
 * Both `finalize*` functions validate `raw` against the same zod schema the
 * Cloud path already used (planToolOutputSchema/chatToolOutputSchema) and
 * throw (a plain ZodError) on a bad shape — deliberately not swallowed
 * here, since what to do about an invalid reply differs by caller: the
 * Cloud path (ai-orchestrator, synchronous) has already run out of
 * options and marks the request failed; the Home path (telegram-webhook)
 * gets a documented one-time retry before giving up
 * (../docs/architecture.md §07) — see that lambda for the retry/fallback
 * logic this intentionally leaves to it.
 */

/** How many messages a Conversation item keeps before dropping the oldest — same cap ai-orchestrator always used, moved here so both callers share one definition rather than two constants that could drift apart. */
export const MAX_STORED_MESSAGES = 40;

export interface FinalizeOutcome {
  resultRef: string;
  body: Record<string, unknown>;
}

export function finalizePlanResult(
  userId: string,
  scope: PlanScope,
  periodStart: string,
  periodEnd: string,
  candidateIds: readonly string[],
  raw: unknown,
  engine: AiMode,
): { record: PlanRecord; outcome: FinalizeOutcome } {
  const parsed = planToolOutputSchema.parse(raw); // throws ZodError on a bad shape — see doc comment above
  const candidateIdSet = new Set(candidateIds);
  const validItems = parsed.items.filter((item) => candidateIdSet.has(item.todoId));
  if (validItems.length < parsed.items.length) {
    console.warn('ai-finalize: dropped plan items referencing unknown todo ids', {
      dropped: parsed.items.length - validItems.length,
      engine,
    });
  }

  const key = planKey(userId, scope, periodStart);
  const record: PlanRecord = {
    ...key,
    entityType: 'PLAN',
    userId,
    scope,
    periodStart,
    periodEnd,
    summary: parsed.summary,
    items: validItems,
    engine,
    generatedAt: new Date().toISOString(),
  };
  return { record, outcome: { resultRef: key.SK, body: stripKeys(record) } };
}

export async function persistPlan(record: PlanRecord): Promise<void> {
  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: record }));
}

/**
 * Unlike finalizePlanResult, this one doesn't validate+persist in the same
 * step — chat's Home-mode path needs to attempt validation, and only on
 * success turn the raw reply into a message; a plain-text fallback (see
 * telegram-webhook's doc comment) bypasses this entirely and calls
 * [buildChatMessages]/[persistConversation] directly with the fallback
 * text instead. Throws ZodError on a bad shape, same as finalizePlanResult.
 *
 * Returns `createTodos`/`suggestedDueDateChanges` alongside `reply`
 * (defaulted to `[]` when the model left either out) so callers can pass
 * them straight to [createRequestedTodos]/[filterValidDueDateChanges]
 * without re-parsing `raw` themselves.
 */
export function validateChatReply(
  raw: unknown,
): { reply: string; createTodos: AiCreateTodoInput[]; suggestedDueDateChanges: DueDateChangeSuggestion[] } {
  const parsed = chatToolOutputSchema.parse(raw);
  return {
    reply: parsed.reply,
    createTodos: parsed.createTodos ?? [],
    suggestedDueDateChanges: parsed.suggestedDueDateChanges ?? [],
  };
}

/**
 * Drops any suggested due-date change referencing a todoId that wasn't in
 * the candidate set actually offered to the model this turn — the same
 * integrity guard finalizePlanResult applies to plan items, applied here
 * for the same reason (a forced-tool-choice response reliably returns
 * *valid JSON*, never a guarantee every id inside it was one the model was
 * actually shown). `candidateIds` is undefined for every subjectType
 * except 'planning' (see lambdas/ai-orchestrator/index.ts's
 * buildSubjectContext) — no candidate set means no suggestion in this
 * reply was ever groundable against real data, so everything is dropped
 * rather than trusted, not just anything referencing an unknown id.
 */
export function filterValidDueDateChanges(
  candidateIds: readonly string[] | undefined,
  changes: DueDateChangeSuggestion[],
): DueDateChangeSuggestion[] {
  if (!candidateIds || changes.length === 0) return [];
  const candidateIdSet = new Set(candidateIds);
  const valid = changes.filter((c) => candidateIdSet.has(c.todoId));
  if (valid.length < changes.length) {
    console.warn('ai-finalize: dropped due-date-change suggestions referencing unknown todo ids', {
      dropped: changes.length - valid.length,
    });
  }
  return valid;
}

/**
 * Actually creates each todo the model asked for via `createTodos` (see
 * ai-schema.ts's chatToolOutputSchema doc comment) — real DynamoDB writes,
 * not a preview the app has to separately confirm. `source: 'ai'` (already
 * a valid TodoSource — see shared/types.ts) marks these as AI-originated,
 * the same way a todo created via the Telegram relay is marked 'telegram'.
 *
 * Deliberately not transactional across multiple requested todos: if one
 * PutCommand fails partway through, earlier ones in this same turn stay
 * created rather than being rolled back — consistent with this codebase's
 * existing stance elsewhere (e.g. recurrence materialization in
 * lambdas/todo-api/index.ts's updateTodo isn't transactional either), and
 * a partial success (some todos created, one missing) is a better outcome
 * here than none at all.
 *
 * Returns the created items stripped of table keys, same convention as
 * lambdas/todo-api/index.ts's own responses, so a caller with a response
 * channel (ai-orchestrator's synchronous Cloud path) can hand them straight
 * back to the client. telegram-webhook's Home path calls this for the
 * side effect only — see its own doc comment on why there's no response
 * channel to put the return value in.
 */
export async function createRequestedTodos(
  userId: string,
  createTodos: AiCreateTodoInput[],
): Promise<Record<string, unknown>[]> {
  const created: Record<string, unknown>[] = [];
  for (const req of createTodos) {
    const item = buildTodoItem(userId, randomUUID(), { ...req, source: 'ai' });
    await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: item }));
    created.push(stripKeys(item));
  }
  return created;
}

export function buildChatMessages(
  priorMessages: readonly ConversationMessage[],
  userMessageText: string,
  replyText: string,
  engine: AiMode,
): ConversationMessage[] {
  const now = new Date().toISOString();
  const userMsg: ConversationMessage = { role: 'user', text: userMessageText, ts: now };
  const assistantMsg: ConversationMessage = { role: 'assistant', text: replyText, engine, ts: now };
  return [...priorMessages, userMsg, assistantMsg].slice(-MAX_STORED_MESSAGES);
}

export async function persistConversation(
  userId: string,
  conversationId: string,
  subjectType: ChatSubjectType,
  subjectId: string | undefined,
  messages: ConversationMessage[],
): Promise<FinalizeOutcome> {
  const key = convoKey(userId, conversationId);
  const record: ConversationRecord = {
    ...key,
    entityType: 'CONVO',
    userId,
    id: conversationId,
    subjectType,
    subjectId,
    messages,
    updatedAt: new Date().toISOString(),
  };
  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: record }));
  const reply = messages[messages.length - 1]?.text ?? '';
  return { resultRef: key.SK, body: { conversationId, subjectType, subjectId, reply } };
}

/**
 * Same convention as lambdas/todo-api and lambdas/ai-orchestrator — never
 * expose raw table keys over the API.
 */
function stripKeys(item: object): Record<string, unknown> {
  const rest: Record<string, unknown> = { ...(item as Record<string, unknown>) };
  delete rest.PK;
  delete rest.SK;
  delete rest.entityType;
  return rest;
}

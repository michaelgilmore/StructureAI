import type { APIGatewayProxyEventV2WithJWTAuthorizer, APIGatewayProxyResultV2 } from 'aws-lambda';
import { randomUUID } from 'node:crypto';
import { GetCommand, PutCommand, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { ZodError } from 'zod';
import { ddb, TABLE_NAME, aiRequestKey, convoKey, todoKey, userKey } from '../../shared/dynamo';
import { resolvePeriod } from '../../shared/plan-period';
import {
  aiRequestSchema,
  CHAT_OUTPUT_JSON_SCHEMA,
  PLAN_OUTPUT_JSON_SCHEMA,
  type AiRequestInput,
  type ChatRequestInput,
  type PlanRequestInput,
} from '../../shared/ai-schema';
import {
  buildChatMessages,
  createRequestedTodos,
  finalizePlanResult,
  persistConversation,
  persistPlan,
  validateChatReply,
  type FinalizeOutcome,
} from '../../shared/ai-finalize';
import { buildFrames, sendFrames, type AiRequestPayload } from '../../shared/telegram';
import { getSecretString } from '../../shared/secrets';
import type {
  ChatHomeContext,
  ConversationMessage,
  ConversationRecord,
  PendingAiRequestItem,
  PlanHomeContext,
  PlanRecord,
  TodoItem,
} from '../../shared/types';
import { callBedrockTool } from './bedrock';

/** Epoch-seconds TTL for PendingAiRequest items — see data-stack.ts's timeToLiveAttribute. 24h is generous for a Cloud-mode request that actually completes in seconds; also generous for the async Home-mode flow, which is held to a much shorter ~2 minute end-to-end timeout by lambdas/ai-request-timeout — this TTL is just the outer "clean up eventually" bound, not the timeout itself. */
const PENDING_REQUEST_TTL_SECONDS = 24 * 60 * 60;
/** How many prior turns to feed back to the model as conversation history — Bedrock's `history` and the Home Bridge Bot's `AiRequestPayload.history` both respect this same cap. Real summarization (architecture.md §07: "summarized once it gets long enough to threaten the context window") isn't built yet — this is a blunt cap that keeps token usage bounded in the meantime. */
const MAX_HISTORY_MESSAGES = 20;
/** Plan candidates sent to the model, capped so a month/year plan (potentially many todos) stays a "lighter-weight summary" per architecture.md §07 rather than every individual item verbatim. */
const MAX_PLAN_CANDIDATES = 60;

/** An expected, user-facing failure (bad reference, missing prerequisite) — carries the HTTP status to report, distinct from an unexpected Bedrock/Telegram/AWS error (which defaults to 502). */
class AiRequestError extends Error {
  constructor(message: string, public statusCode: number = 502) {
    super(message);
  }
}

/**
 * `POST /ai/request` and `GET /ai/request/{reqId}` — plan generation and
 * dialog, backed by either Bedrock (Cloud mode, synchronous) or the Home
 * Bridge Bot over Telegram (Home mode, genuinely async). See
 * ../../docs/architecture.md §03 (Cloud vs Home), §04 (the Telegram wire
 * protocol), §05-§07 (data shapes, the "four scopes"), and
 * ../../docs/api/README.md for the wire contract.
 *
 * Cloud mode: Bedrock calls are fast enough (1-4s typical, per
 * architecture.md §03) that both request kinds run synchronously inside
 * this Lambda rather than fanning out to a separate worker.
 *
 * Home mode: this Lambda only *dispatches* the request (builds the same
 * prompt/schema Bedrock would have gotten, sends it to the Home Bridge Bot
 * as chunked Telegram frames — see ../../shared/telegram.ts — and returns
 * immediately with status='sent'). Finishing the request — validating the
 * Home Bridge Bot's reply and turning it into a Plan/Conversation record —
 * happens later, in ../telegram-webhook/, when that bot's `ai_result` frame
 * arrives. Both paths write `PendingAiRequest` first so `GET
 * /ai/request/{id}` has something to show immediately either way.
 */
export const handler = async (
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
): Promise<APIGatewayProxyResultV2> => {
  const userId = event.requestContext.authorizer.jwt.claims.sub as string;
  const reqId = event.pathParameters?.reqId;

  try {
    switch (event.routeKey) {
      case 'POST /ai/request':
        return await createRequest(userId, parseBody(event));
      case 'GET /ai/request/{reqId}':
        return reqId ? await getRequest(userId, reqId) : badRequest('missing reqId');
      default:
        return json(404, { error: `no route for ${event.routeKey}` });
    }
  } catch (err) {
    if (err instanceof ZodError) {
      return json(400, { error: 'validation failed', details: err.issues });
    }
    console.error('ai-orchestrator error', err);
    return json(500, { error: 'internal error' });
  }
};

// ---------- route handlers ----------

async function createRequest(userId: string, body: unknown): Promise<APIGatewayProxyResultV2> {
  const input = aiRequestSchema.parse(body);
  const reqId = randomUUID();
  const now = new Date().toISOString();
  const baseItem: PendingAiRequestItem = {
    ...aiRequestKey(userId, reqId),
    entityType: 'PENDING_AI_REQUEST',
    userId,
    reqId,
    kind: input.kind,
    mode: input.mode,
    status: 'sent',
    createdAt: now,
    ttl: Math.floor(Date.now() / 1000) + PENDING_REQUEST_TTL_SECONDS,
  };
  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: baseItem }));

  try {
    if (input.mode === 'home') {
      const { homeContext, homePrompt } = await dispatchHomeRequest(userId, input, reqId);
      const pendingItem: PendingAiRequestItem = {
        ...baseItem,
        homeContext,
        homePrompt,
        homeRetryCount: 0,
        // Sparse GSI1 — see shared/types.ts's doc comment on
        // PendingAiRequestItem. Only set once dispatch actually succeeds;
        // a request that failed to dispatch (caught below) never becomes
        // "stuck", so it has no business in the timeout scanner's index.
        GSI1PK: 'PENDING_HOME_REQUEST',
        GSI1SK: now,
      };
      await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: pendingItem }));
      return json(200, { reqId, status: 'sent', kind: input.kind });
    }

    const outcome = input.kind === 'plan' ? await generatePlan(userId, input) : await runChat(userId, input);
    await ddb.send(new PutCommand({
      TableName: TABLE_NAME,
      Item: { ...baseItem, status: 'done', resultRef: outcome.resultRef },
    }));
    return json(200, { reqId, status: 'done', kind: input.kind, result: outcome.body });
  } catch (err) {
    const message = errorMessage(err);
    await ddb.send(new PutCommand({
      TableName: TABLE_NAME,
      Item: { ...baseItem, status: 'failed', error: message.slice(0, 500) },
    }));

    const statusCode = err instanceof AiRequestError ? err.statusCode : 502;
    console.error('ai-orchestrator request failed', { reqId, kind: input.kind, mode: input.mode, err });
    return json(statusCode, { reqId, status: 'failed', error: message });
  }
}

async function getRequest(userId: string, reqId: string): Promise<APIGatewayProxyResultV2> {
  const result = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: aiRequestKey(userId, reqId) }));
  const item = result.Item as PendingAiRequestItem | undefined;
  if (!item) return json(404, { error: 'not found' });

  if (item.status !== 'done' || !item.resultRef) {
    return json(200, { reqId, status: item.status, kind: item.kind, mode: item.mode, error: item.error });
  }

  // 'done' — resolve resultRef to the actual Plan/Conversation record so a
  // poller gets full data, not just a pointer. Note this is the raw stored
  // record, not the condensed `result` shape POST returns for chat (see
  // ../../docs/api/README.md) — documented there rather than reshaped here.
  const record = await ddb.send(new GetCommand({
    TableName: TABLE_NAME,
    Key: { PK: userKey(userId), SK: item.resultRef },
  }));
  return json(200, {
    reqId,
    status: item.status,
    kind: item.kind,
    mode: item.mode,
    result: record.Item ? stripKeys(record.Item) : undefined,
  });
}

// ---------- plan generation ----------

interface PlanPrompt {
  system: string;
  userMessage: string;
  periodStart: string;
  periodEnd: string;
  /** The exact todo ids offered to the model — both callBedrockTool's caller and Home mode's PlanHomeContext need this same gating list, so it's computed once here. */
  candidateIds: string[];
}

/** Builds the plan prompt/schema/candidate-gating shared by both engines. Doesn't call any model — that's the one thing that differs between Cloud (Bedrock, here) and Home (Telegram, dispatchHomeRequest). */
async function buildPlanPrompt(userId: string, input: PlanRequestInput): Promise<PlanPrompt> {
  const { periodStart, periodEnd } = resolvePeriod(input.scope, input.periodStart);

  const allTodos = await queryTodos(userId);
  const candidates = allTodos
    .filter((t) => t.status === 'open' && !!t.dueDate && t.dueDate <= periodEnd)
    .sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? ''));

  const omittedCount = Math.max(0, candidates.length - MAX_PLAN_CANDIDATES);
  const included = candidates.slice(0, MAX_PLAN_CANDIDATES);
  const candidateIds = included.map((t) => t.id);

  const compactTodos = included.map((t) => ({
    id: t.id,
    title: t.title,
    priority: t.priority,
    dueDate: t.dueDate,
    project: t.project ?? undefined,
    overdue: !!t.dueDate && t.dueDate < periodStart,
  }));

  const system =
    'You are the planning engine for Structure, a personal todo manager. Given a date range and a list of the ' +
    "user's open todos (some overdue, carried forward from before the range), produce an ordered agenda for " +
    'that range with a short rationale (one sentence) per item you include. Only reference todo ids from the ' +
    'list you were given — never invent a todo or an id. It is fine to leave low-priority items out of a ' +
    'busy day; it is not fine to include an id that was not provided.';

  const userMessage = JSON.stringify({
    scope: input.scope,
    periodStart,
    periodEnd,
    focus: input.focus,
    todos: compactTodos,
    omittedFromThisList: omittedCount || undefined,
  });

  return { system, userMessage, periodStart, periodEnd, candidateIds };
}

async function generatePlan(userId: string, input: PlanRequestInput): Promise<FinalizeOutcome> {
  const prompt = await buildPlanPrompt(userId, input);

  const raw = await callBedrockTool<unknown>({
    system: prompt.system,
    userMessage: prompt.userMessage,
    toolName: 'generate_plan',
    toolDescription: 'Return the generated plan: a short summary and an ordered list of agenda items.',
    inputSchema: PLAN_OUTPUT_JSON_SCHEMA,
    maxTokens: 1500,
  });

  const { record, outcome } = finalizePlanResult(
    userId, input.scope, prompt.periodStart, prompt.periodEnd, prompt.candidateIds, raw, 'cloud',
  );
  await persistPlan(record);
  return outcome;
}

// ---------- chat / dialog ----------

async function buildChatSystemPrompt(userId: string, input: ChatRequestInput): Promise<string> {
  const contextText = await buildSubjectContext(userId, input);
  const today = new Date().toISOString().slice(0, 10);
  return (
    'You are the dialog assistant for Structure, a personal todo manager. Answer the user helpfully and concisely, ' +
    "grounded in the context below. If the context doesn't cover something, say so rather than guessing.\n\n" +
    `Today's date is ${today} (UTC) — use it to resolve relative dates like "tomorrow" or "next Friday" into ` +
    'YYYY-MM-DD for createTodos.dueDate below.\n\n' +
    "You can create new todos directly: if the user clearly asks you to add, create, or remember a task, put it " +
    'in createTodos (in addition to confirming it in your reply text) — this actually creates it, not just a ' +
    "suggestion. Don't invent a task the user didn't ask for, and leave createTodos empty when they're just " +
    'discussing or asking a question rather than asking you to add something.\n\n' +
    `Context:\n${contextText}`
  );
}

async function runChat(userId: string, input: ChatRequestInput): Promise<FinalizeOutcome> {
  const conversation = await loadOrCreateConversation(userId, input);
  const system = await buildChatSystemPrompt(userId, input);

  const history = conversation.messages.slice(-MAX_HISTORY_MESSAGES).map((m) => ({
    role: m.role,
    content: [{ text: m.text }],
  }));

  const raw = await callBedrockTool<unknown>({
    system,
    history,
    userMessage: input.message,
    toolName: 'send_reply',
    toolDescription: 'Return your reply to the user.',
    inputSchema: CHAT_OUTPUT_JSON_SCHEMA,
    maxTokens: 1000,
  });

  const { reply, createTodos } = validateChatReply(raw);
  const created = await createRequestedTodos(userId, createTodos);
  const messages = buildChatMessages(conversation.messages, input.message, reply, 'cloud');
  const outcome = await persistConversation(userId, conversation.id, conversation.subjectType, conversation.subjectId, messages);
  // Cloud mode is synchronous, so this response is the one place the app
  // actually sees what got created — merged in only when non-empty so the
  // wire shape is unchanged for the common "just a reply" turn.
  return created.length ? { ...outcome, body: { ...outcome.body, createdTodos: created } } : outcome;
}

async function loadOrCreateConversation(
  userId: string,
  input: ChatRequestInput,
): Promise<{ id: string; subjectType: ChatRequestInput['subjectType']; subjectId?: string; messages: ConversationMessage[] }> {
  if (input.conversationId) {
    const result = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: convoKey(userId, input.conversationId) }));
    const existing = result.Item as ConversationRecord | undefined;
    if (!existing) throw new AiRequestError('conversation not found', 404);
    return existing;
  }
  return { id: randomUUID(), subjectType: input.subjectType, subjectId: input.subjectId, messages: [] };
}

/** Builds a short text block describing whatever this conversation is grounded in — see ChatSubjectType in shared/types.ts. */
async function buildSubjectContext(userId: string, input: ChatRequestInput): Promise<string> {
  switch (input.subjectType) {
    case 'todo': {
      if (!input.subjectId) throw new AiRequestError('subjectId is required when subjectType is "todo"', 400);
      const result = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: todoKey(userId, input.subjectId) }));
      const todo = result.Item as TodoItem | undefined;
      if (!todo) throw new AiRequestError('todo not found', 404);
      return [
        `A single todo: "${todo.title}"`,
        `status=${todo.status} priority=${todo.priority}`,
        todo.dueDate ? `due ${todo.dueDate}` : 'no due date',
        todo.project ? `project=${todo.project}` : undefined,
        todo.notes ? `notes: ${todo.notes}` : undefined,
      ].filter(Boolean).join('. ');
    }
    case 'plan': {
      // subjectId is a Plan's `{scope}#{periodStart}` — see ChatRequestInput's
      // doc comment in shared/ai-schema.ts and docs/api/README.md.
      if (!input.subjectId) throw new AiRequestError('subjectId is required when subjectType is "plan"', 400);
      const result = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: { PK: userKey(userId), SK: `PLAN#${input.subjectId}` } }));
      const plan = result.Item as PlanRecord | undefined;
      if (!plan) throw new AiRequestError('plan not found — generate one first via kind:"plan"', 404);
      const items = plan.items.map((i) => `- ${i.todoId}: ${i.rationale}`).join('\n');
      return `A generated plan (${plan.scope}, ${plan.periodStart} to ${plan.periodEnd}): ${plan.summary}\n${items}`;
    }
    case 'general': {
      const allTodos = await queryTodos(userId);
      const open = allTodos.filter((t) => t.status === 'open');
      const upcoming = open
        .filter((t) => !!t.dueDate)
        .sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? ''))
        .slice(0, 5)
        .map((t) => `${t.title} (due ${t.dueDate})`);
      return `${open.length} open todos in total. Next up: ${upcoming.join('; ') || 'nothing with a due date yet'}.`;
    }
  }
}

// ---------- Home mode dispatch (Phase 3) ----------

interface HomeDispatchResult {
  homeContext: PlanHomeContext | ChatHomeContext;
  homePrompt: NonNullable<PendingAiRequestItem['homePrompt']>;
}

/**
 * Builds the same prompt/schema the Cloud path would have handed Bedrock,
 * sends it to the Home Bridge Bot as chunked Telegram frames instead, and
 * returns what createRequest needs to persist so lambdas/telegram-webhook
 * can finish this request later without re-deriving anything: `homeContext`
 * (a plan's candidateIds especially — see PlanHomeContext's doc comment in
 * shared/types.ts) and `homePrompt` (the verbatim system/userMessage/history
 * sent below, kept so a schema-validation retry resends the exact same
 * content — see PendingAiRequest.homePrompt's doc comment). Never touches
 * Bedrock.
 */
async function dispatchHomeRequest(userId: string, input: AiRequestInput, reqId: string): Promise<HomeDispatchResult> {
  let payload: AiRequestPayload;
  let homeContext: PlanHomeContext | ChatHomeContext;
  let homePrompt: HomeDispatchResult['homePrompt'];

  if (input.kind === 'plan') {
    const prompt = await buildPlanPrompt(userId, input);
    payload = { kind: 'plan', system: prompt.system, userMessage: prompt.userMessage, outputSchema: PLAN_OUTPUT_JSON_SCHEMA };
    homeContext = {
      kind: 'plan',
      scope: input.scope,
      periodStart: prompt.periodStart,
      periodEnd: prompt.periodEnd,
      candidateIds: prompt.candidateIds,
    };
    homePrompt = { system: prompt.system, userMessage: prompt.userMessage };
  } else {
    const conversation = await loadOrCreateConversation(userId, input);
    const system = await buildChatSystemPrompt(userId, input);
    const history = conversation.messages.slice(-MAX_HISTORY_MESSAGES).map((m) => ({ role: m.role, text: m.text }));
    payload = { kind: 'chat', system, userMessage: input.message, outputSchema: CHAT_OUTPUT_JSON_SCHEMA, history };
    homeContext = {
      kind: 'chat',
      conversationId: conversation.id,
      subjectType: conversation.subjectType,
      subjectId: conversation.subjectId,
      priorMessages: conversation.messages,
      userMessage: input.message,
    };
    homePrompt = { system, userMessage: input.message, history };
  }

  const botToken = await getSecretString(requireEnv('TELEGRAM_BOT_TOKEN_SECRET_ARN'));
  // `@username` form, not a numeric chat id — see shared/telegram.ts's
  // sendFrames doc comment on why that's enough for bot-to-bot sendMessage.
  // Kept as a *username* (rather than asking the user to look up a numeric
  // id) so this one setting also doubles as what ../telegram-webhook/
  // checks an inbound update's `message.from.username` against, without a
  // second piece of config that could drift out of sync with this one.
  const chatId = `@${requireEnv('HOME_BRIDGE_BOT_USERNAME')}`;
  await sendFrames(botToken, chatId, buildFrames('ai_request', userId, reqId, payload));
  return { homeContext, homePrompt };
}

// ---------- helpers ----------

async function queryTodos(userId: string): Promise<TodoItem[]> {
  const result = await ddb.send(new QueryCommand({
    TableName: TABLE_NAME,
    KeyConditionExpression: 'PK = :pk AND begins_with(SK, :prefix)',
    ExpressionAttributeValues: { ':pk': userKey(userId), ':prefix': 'TODO#' },
  }));
  return (result.Items ?? []) as TodoItem[];
}

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`missing required env var ${name}`);
  return value;
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}

function parseBody(event: APIGatewayProxyEventV2WithJWTAuthorizer): unknown {
  const raw = event.body ?? '{}';
  const text = event.isBase64Encoded ? Buffer.from(raw, 'base64').toString('utf8') : raw;
  return text.trim().length ? JSON.parse(text) : {};
}

/**
 * Same convention as lambdas/todo-api/index.ts — never expose raw table
 * keys over the API. Parameter is deliberately the loose `object` rather
 * than `Record<string, unknown>`: this is called with PlanRecord,
 * ConversationRecord, and a plain DynamoDB GetCommand result, none of
 * which have (or should have) a string index signature, and TypeScript
 * requires the source of an assignment to a `Record<string, X>` parameter
 * to carry one too — `object` has no such requirement.
 */
function stripKeys(item: object): Record<string, unknown> {
  const rest: Record<string, unknown> = { ...(item as Record<string, unknown>) };
  delete rest.PK;
  delete rest.SK;
  delete rest.entityType;
  return rest;
}

function json(statusCode: number, body: unknown): APIGatewayProxyResultV2 {
  return { statusCode, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) };
}

function badRequest(error: string): APIGatewayProxyResultV2 {
  return json(400, { error });
}

import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { timingSafeEqual } from 'node:crypto';
import { PublishCommand, SNSClient } from '@aws-sdk/client-sns';
import { GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';
import { ZodError } from 'zod';
import { ddb, TABLE_NAME, aiRequestKey, homeStatusKey } from '../../shared/dynamo';
import { getSecretString } from '../../shared/secrets';
import { CHAT_OUTPUT_JSON_SCHEMA, PLAN_OUTPUT_JSON_SCHEMA } from '../../shared/ai-schema';
import {
  buildChatMessages,
  createRequestedTodos,
  finalizePlanResult,
  persistConversation,
  persistPlan,
  validateChatReply,
} from '../../shared/ai-finalize';
import {
  assembleFrame,
  buildFrames,
  sendFrames,
  type AiRequestPayload,
  type AiResultPayload,
  type HeartbeatPayload,
  type TelegramFrame,
} from '../../shared/telegram';
import type { ChatHomeContext, HomeStatusItem, PendingAiRequestItem } from '../../shared/types';

const sns = new SNSClient({});

/**
 * `POST /telegram/webhook` — no Cognito authorizer (see
 * ../../infra/lib/api-stack.ts); Telegram, not the app, calls this.
 * Authenticity instead comes from the `X-Telegram-Bot-Api-Secret-Token`
 * header, set once via `setWebhook`'s `secret_token` param when you point
 * the Cloud Relay Bot's webhook at this URL — see ../../README.md's
 * "Telegram bridge" setup section.
 *
 * The other half of ../ai-orchestrator/index.ts's Home-mode dispatch (see
 * ../../docs/architecture.md §04/§07): reassembles chunked frames (see
 * ../../shared/telegram.ts), records the Home Bridge Bot's heartbeats, and
 * finalizes a Home-mode plan/chat request once its `ai_result` frame
 * arrives — the same validate-then-persist logic
 * ../ai-orchestrator/index.ts's Cloud path uses (../../shared/ai-finalize.ts),
 * plus the one-retry-then-fallback behavior §07 describes for a reply that
 * fails schema validation.
 *
 * Response codes matter here beyond the usual REST convention, because
 * Telegram retries webhook delivery on anything other than a prompt 200:
 * an update this handler understood but can't act on (wrong sender, a
 * request that's no longer pending, an unparseable frame) returns 200 —
 * retrying won't change the outcome, so there's no reason to make Telegram
 * keep trying. A genuinely unexpected failure (DynamoDB unreachable, a
 * Telegram sendMessage call failing when retrying a bad reply) returns 500,
 * so Telegram's retry gives this a second chance later.
 */
export const handler = async (event: APIGatewayProxyEventV2): Promise<APIGatewayProxyResultV2> => {
  try {
    const provided = getHeader(event.headers, 'x-telegram-bot-api-secret-token');
    const expected = await getSecretString(requireEnv('TELEGRAM_WEBHOOK_SECRET_ARN'));
    if (!provided || !secureEquals(provided, expected)) {
      console.warn('telegram-webhook: rejected a request with a missing/invalid secret token');
      return json(401, { error: 'unauthorized' });
    }

    let update: TelegramUpdate;
    try {
      update = parseBody(event) as TelegramUpdate;
    } catch (err) {
      console.warn('telegram-webhook: request body was not valid JSON, ignoring', err);
      return json(200, { ok: true });
    }

    const message = update.message;
    const text = message?.text;
    if (!message || typeof text !== 'string') {
      // Not a text message — an edited_message, a sticker, a bot added to
      // a group, etc. Nothing here to act on.
      return json(200, { ok: true });
    }

    const expectedUsername = requireEnv('HOME_BRIDGE_BOT_USERNAME');
    if ((message.from?.username ?? '').toLowerCase() !== expectedUsername.toLowerCase()) {
      console.warn('telegram-webhook: ignoring a message from an unexpected sender', {
        username: message.from?.username,
      });
      return json(200, { ok: true });
    }

    let frame: TelegramFrame;
    try {
      frame = JSON.parse(text) as TelegramFrame;
    } catch {
      console.warn('telegram-webhook: message text was not a valid frame, ignoring', { textPreview: text.slice(0, 200) });
      return json(200, { ok: true });
    }
    if (frame.v !== 1 || !frame.type || !frame.userId) {
      console.warn('telegram-webhook: malformed frame, ignoring', { frame });
      return json(200, { ok: true });
    }

    const payload = await assembleFrame(frame);
    if (payload === undefined) return json(200, { ok: true }); // still buffering more chunks of this frame

    if (frame.type === 'heartbeat') {
      await handleHeartbeat(frame.userId, payload as HeartbeatPayload);
    } else if (frame.type === 'ai_result') {
      if (!frame.reqId) {
        console.warn('telegram-webhook: ai_result frame is missing reqId, ignoring');
      } else {
        await handleAiResult(frame.userId, frame.reqId, payload as AiResultPayload);
      }
    } else {
      console.warn('telegram-webhook: unrecognized frame type, ignoring', { type: frame.type });
    }

    return json(200, { ok: true });
  } catch (err) {
    console.error('telegram-webhook: unexpected error — returning 5xx so Telegram retries this update', err);
    return json(500, { error: 'internal error' });
  }
};

// ---------- frame handlers ----------

async function handleHeartbeat(userId: string, payload: HeartbeatPayload): Promise<void> {
  const item: HomeStatusItem = {
    ...homeStatusKey(userId),
    entityType: 'HOME_STATUS',
    userId,
    lastHeartbeatAt: new Date().toISOString(),
    model: payload.model,
    ollamaOk: payload.ollamaOk,
  };
  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: item }));
}

async function handleAiResult(userId: string, reqId: string, payload: AiResultPayload): Promise<void> {
  const result = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: aiRequestKey(userId, reqId) }));
  const item = result.Item as PendingAiRequestItem | undefined;
  if (!item) {
    console.warn('telegram-webhook: ai_result for an unknown reqId, ignoring', { reqId });
    return;
  }
  if (item.status !== 'sent') {
    // Already finalized (done/failed) — a duplicate delivery (Telegram can
    // redeliver a webhook) or a reply that arrived after
    // ../ai-request-timeout/ already gave up on this request. Either way,
    // acting on it again would be wrong (double-counting a result, or
    // overwriting a timeout's failure with a late success) — this is the
    // per-reqId idempotency check assembleFrame's doc comment defers to
    // its callers.
    console.warn('telegram-webhook: ai_result for a request that is no longer pending, ignoring', {
      reqId, status: item.status,
    });
    return;
  }
  if (!item.homeContext) {
    console.error('telegram-webhook: pending Home-mode request has no homeContext — cannot finalize', { reqId });
    await markFailed(item, 'internal error: missing homeContext');
    return;
  }

  if (!payload.ok) {
    await markFailed(item, payload.error.slice(0, 500));
    await pushAiResultNotification(userId, 'Home AI request failed', payload.error.slice(0, 180));
    return;
  }

  try {
    if (item.homeContext.kind === 'plan') {
      const ctx = item.homeContext;
      const { record, outcome } = finalizePlanResult(
        userId, ctx.scope, ctx.periodStart, ctx.periodEnd, ctx.candidateIds, payload.raw, 'home',
      );
      await persistPlan(record);
      await markDone(item, outcome.resultRef);
      await pushAiResultNotification(userId, 'Plan ready', record.summary.slice(0, 180));
    } else {
      const ctx = item.homeContext;
      const { reply, createTodos } = validateChatReply(payload.raw);
      // Side effect only — unlike ai-orchestrator's Cloud path, this handler
      // has no synchronous response channel back to the Android app (it
      // responds to Telegram's webhook call, not to the client that sent
      // the original chat message). The model's own `reply` text plus the
      // app's next GET /todos sync are how the user finds out what got
      // created; see shared/ai-finalize.ts's createRequestedTodos doc comment.
      await createRequestedTodos(userId, createTodos);
      const messages = buildChatMessages(ctx.priorMessages, ctx.userMessage, reply, 'home');
      const outcome = await persistConversation(userId, ctx.conversationId, ctx.subjectType, ctx.subjectId, messages);
      await markDone(item, outcome.resultRef);
      await pushAiResultNotification(userId, 'Reply ready', reply.slice(0, 180));
    }
  } catch (err) {
    if (!(err instanceof ZodError)) throw err; // not a validation failure — let the outer catch turn this into a 500 so Telegram retries
    await handleInvalidReply(userId, reqId, item, payload.raw, err);
  }
}

/**
 * ../../docs/architecture.md §07's retry/fallback rule: one stricter retry
 * on the first schema-validation failure; on a second failure, plan fails
 * outright (there's no safe way to guess at agenda items) but chat falls
 * back to showing the raw reply as plain text (see extractFallbackReplyText
 * below) rather than losing the turn entirely.
 */
async function handleInvalidReply(
  userId: string,
  reqId: string,
  item: PendingAiRequestItem,
  raw: unknown,
  validationError: ZodError,
): Promise<void> {
  const alreadyRetried = (item.homeRetryCount ?? 0) > 0;
  if (!alreadyRetried && item.homePrompt) {
    console.warn('telegram-webhook: Home reply failed schema validation — retrying once with a stricter prompt', {
      reqId, issues: validationError.issues,
    });
    await retryWithStricterPrompt(userId, reqId, item);
    return;
  }

  console.warn('telegram-webhook: Home reply failed schema validation again (or had no homePrompt to retry with) — giving up', {
    reqId, issues: validationError.issues,
  });

  if (item.kind === 'plan') {
    const reason = validationError.issues.map((i) => i.message).join('; ');
    await markFailed(item, `Home Bridge Bot's reply did not match the expected plan shape, even after a retry: ${reason}`.slice(0, 500));
    await pushAiResultNotification(userId, 'Plan failed', "The Home AI's reply couldn't be understood, even after a retry.");
    return;
  }

  const ctx = item.homeContext as ChatHomeContext;
  const fallbackReply = extractFallbackReplyText(raw);
  const messages = buildChatMessages(ctx.priorMessages, ctx.userMessage, fallbackReply, 'home');
  const outcome = await persistConversation(userId, ctx.conversationId, ctx.subjectType, ctx.subjectId, messages);
  await markDone(item, outcome.resultRef);
  await pushAiResultNotification(userId, 'Reply ready', fallbackReply.slice(0, 180));
}

/** Resends the exact original prompt (see PendingAiRequest.homePrompt's doc comment in shared/types.ts) with one stricter instruction appended, and bumps homeRetryCount so a second failure stops retrying. */
async function retryWithStricterPrompt(userId: string, reqId: string, item: PendingAiRequestItem): Promise<void> {
  const prompt = item.homePrompt;
  if (!prompt) throw new Error('retryWithStricterPrompt called without a homePrompt'); // guarded by the caller; narrows the type here

  const strictSystem =
    `${prompt.system}\n\nIMPORTANT: Your previous reply did not match the required JSON shape. Reply with ONLY a ` +
    'single valid JSON object matching the schema — no prose, no markdown code fences, no commentary before or after it.';
  const outputSchema = item.kind === 'plan' ? PLAN_OUTPUT_JSON_SCHEMA : CHAT_OUTPUT_JSON_SCHEMA;
  const payload: AiRequestPayload = {
    kind: item.kind,
    system: strictSystem,
    userMessage: prompt.userMessage,
    outputSchema,
    history: prompt.history,
  };

  const botToken = await getSecretString(requireEnv('TELEGRAM_BOT_TOKEN_SECRET_ARN'));
  const chatId = `@${requireEnv('HOME_BRIDGE_BOT_USERNAME')}`;
  await sendFrames(botToken, chatId, buildFrames('ai_request', userId, reqId, payload));

  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: { ...item, homeRetryCount: 1 } }));
}

/**
 * Best-effort extraction of *something* usable when a reply fails schema
 * validation twice — a bare string reply, or an object that's close to the
 * expected `{reply}` shape but not quite (wrong length, extra fields,
 * etc.), still usually contains real conversational text worth showing.
 * The last-resort `JSON.stringify` branch is a deliberately visible "this
 * didn't quite work" result rather than a silently dropped turn.
 */
function extractFallbackReplyText(raw: unknown): string {
  if (typeof raw === 'string') return raw.trim().slice(0, 4000) || "(the Home AI's reply was empty)";
  if (raw && typeof raw === 'object') {
    const maybeReply = (raw as Record<string, unknown>).reply;
    if (typeof maybeReply === 'string' && maybeReply.trim()) return maybeReply.trim().slice(0, 4000);
  }
  return JSON.stringify(raw).slice(0, 4000);
}

// ---------- persistence helpers ----------

async function markDone(item: PendingAiRequestItem, resultRef: string): Promise<void> {
  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: { ...clearHomeIndex(item), status: 'done', resultRef } }));
}

async function markFailed(item: PendingAiRequestItem, error: string): Promise<void> {
  await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: { ...clearHomeIndex(item), status: 'failed', error } }));
}

/** Drops the sparse GSI1 keys — same "clear the index once resolved" precedent as lambdas/reminder-scan's clearReminderIndex, just via a full Put (markDone/markFailed are already rewriting the whole item) rather than an Update. */
function clearHomeIndex(item: PendingAiRequestItem): PendingAiRequestItem {
  const { GSI1PK, GSI1SK, ...rest } = item;
  return rest;
}

/**
 * Publishes to the same reminders topic lambdas/reminder-scan already uses
 * (see ../../infra/lib/telegram-stack.ts's TOPIC_ARN wiring) so no new SNS
 * topic or app-side subscription is needed. Deliberately omits `todoId` —
 * TodoFcmService.onMessageReceived and NotificationHelper.showReminder
 * (android-app) both already treat a missing todoId as "just open the app"
 * rather than erroring, so this degrades gracefully on the current,
 * unmodified Android app; wiring a dedicated "AI result" UI/deep link is
 * later Android work, not this backend round.
 *
 * `GCM`'s value has to be `{fcmV1Message: {message: {data: {...}}}}`, not a
 * flat `{data: {...}}` — see lambdas/reminder-scan/index.ts's top doc
 * comment for why (this platform application authenticates via a Firebase
 * service account, i.e. FCM's v1 API, which SNS requires this specific
 * wrapper for).
 */
async function pushAiResultNotification(userId: string, title: string, body: string): Promise<void> {
  await sns.send(new PublishCommand({
    TopicArn: requireEnv('TOPIC_ARN'),
    MessageStructure: 'json',
    Message: JSON.stringify({
      default: `${title} — ${body}`,
      GCM: JSON.stringify({ fcmV1Message: { message: { data: { title, body } } } }),
    }),
    MessageAttributes: { userId: { DataType: 'String', StringValue: userId } },
  }));
}

// ---------- helpers ----------

/** Minimal shape of a Telegram Update — only what this handler reads. See https://core.telegram.org/bots/api#update / #message. */
interface TelegramUpdate {
  update_id: number;
  message?: {
    message_id: number;
    text?: string;
    from?: { id: number; username?: string; is_bot?: boolean };
  };
}

function secureEquals(a: string, b: string): boolean {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

/** API Gateway HTTP APIs (v2) lowercase header names, but this looks up case-insensitively anyway rather than betting on that. */
function getHeader(headers: Record<string, string | undefined> | undefined, name: string): string | undefined {
  if (!headers) return undefined;
  const lower = name.toLowerCase();
  for (const [k, v] of Object.entries(headers)) {
    if (k.toLowerCase() === lower) return v;
  }
  return undefined;
}

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`missing required env var ${name}`);
  return value;
}

function parseBody(event: APIGatewayProxyEventV2): unknown {
  const raw = event.body ?? '{}';
  const text = event.isBase64Encoded ? Buffer.from(raw, 'base64').toString('utf8') : raw;
  return text.trim().length ? JSON.parse(text) : {};
}

function json(statusCode: number, body: unknown): APIGatewayProxyResultV2 {
  return { statusCode, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) };
}

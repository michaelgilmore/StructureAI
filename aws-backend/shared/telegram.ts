import { DeleteCommand, GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';
import { ddb, TABLE_NAME, chunkBufferKey } from './dynamo';

/**
 * The wire protocol between the Cloud Relay Bot (this backend) and the Home
 * Bridge Bot (a small always-on process at your house — Phase 3's other
 * half, not yet built; see ../docs/architecture.md §04). Both sides speak
 * this same frame shape over Telegram's `sendMessage`/webhook-delivered
 * `message.text` — nothing here is Lambda-specific, so the Home Bridge Bot
 * (whatever language it ends up written in) just needs to produce and
 * parse the same JSON.
 *
 * *** UNVERIFIED AGAINST REAL TELEGRAM BOT-TO-BOT TRAFFIC ***
 * This module was written against Telegram's official Bot API docs
 * (https://core.telegram.org/bots/api#sendmessage — chat_id accepts an
 * `@username`, text is 1-4096 chars) and the bot-to-bot feature page
 * (https://core.telegram.org/api/bots/bot-to-bot — private-chat bot-to-bot
 * messages arrive as completely ordinary `message` updates via normal
 * webhook delivery, no special Update shape), both fetched and read before
 * writing this, not guessed. What's genuinely unverified: bot-to-bot
 * messaging is a very new (May 2026) Telegram feature, so there's no
 * hands-on confirmation here that two real bots with "Bot-to-Bot
 * Communication Mode" enabled behave exactly as documented — that only
 * happens once you've set up both bots and we test this for real. See
 * aws-backend/README.md's "Telegram bridge" section for the manual setup
 * steps and what to watch for.
 *
 * Why frames are chunked at all: a plan/chat prompt (a week's todos, or 20
 * turns of conversation history) can exceed Telegram's 4096-char message
 * cap. Rather than trying to fit everything in one message, every payload
 * is JSON-stringified once, sliced into ≤`FRAME_CHUNK_SIZE`-char pieces,
 * and each piece is wrapped in its own small envelope with a `seq`/
 * `totalChunks` header — the receiving side buffers pieces (see
 * `assembleFrame` below) until it has them all, then concatenates and
 * parses. `FRAME_CHUNK_SIZE` leaves generous headroom under 4096 for the
 * envelope's own JSON punctuation/keys, which vary a little by `seq`
 * (single vs double-digit) — headroom, not a tight fit, so that variance
 * never matters.
 */
export const FRAME_CHUNK_SIZE = 3500;

export type TelegramFrameType = 'ai_request' | 'ai_result' | 'heartbeat';

export interface TelegramFrame {
  /** Schema version — bump if this shape ever needs a breaking change, so both sides can tell an old frame from a new one during a rollout. */
  v: 1;
  type: TelegramFrameType;
  /** The Cognito sub these two bots are relaying on behalf of. Always set — see chunkBufferKey's doc comment on why a heartbeat still needs this even though it has no reqId. */
  userId: string;
  /** Present for 'ai_request'/'ai_result'; absent for 'heartbeat', which isn't tied to any one AI request. */
  reqId?: string;
  seq: number;
  totalChunks: number;
  chunk: string;
}

/** Payload once an 'ai_request' frame's chunks are fully reassembled and JSON.parsed. */
export interface AiRequestPayload {
  kind: 'plan' | 'chat';
  system: string;
  userMessage: string;
  /** JSON Schema describing the expected reply shape — the same schema object ai-orchestrator/bedrock.ts passes as a Bedrock tool's inputSchema, reused here since it's already a clean machine-readable contract (and Ollama's own `format` request parameter accepts a JSON Schema directly in recent versions, which the Home Bridge Bot can hand this straight to). */
  outputSchema: Record<string, unknown>;
  /** `kind: 'chat'` only — prior turns, oldest first, NOT including `userMessage`. Deliberately a plain `{role, text}` list rather than Bedrock's `Message` shape (`content: [{text}]`) — this wire protocol isn't Bedrock-specific, and the Home Bridge Bot is free to reshape it however Ollama's `/api/chat` wants. */
  history?: { role: 'user' | 'assistant'; text: string }[];
}

/** Payload once an 'ai_result' frame's chunks are fully reassembled and JSON.parsed. */
export type AiResultPayload =
  | { ok: true; raw: unknown }
  | { ok: false; error: string };

/** Payload once a 'heartbeat' frame's chunks are fully reassembled and JSON.parsed — in practice always a single chunk, small enough to never need splitting. */
export interface HeartbeatPayload {
  ollamaOk: boolean;
  model?: string;
}

/** Slices `JSON.stringify(payload)` into ≤FRAME_CHUNK_SIZE-char pieces and wraps each as a full frame ready to send, in order. */
export function buildFrames(
  type: TelegramFrameType,
  userId: string,
  reqId: string | undefined,
  payload: unknown,
): TelegramFrame[] {
  const json = JSON.stringify(payload);
  const pieces: string[] = [];
  for (let i = 0; i < json.length; i += FRAME_CHUNK_SIZE) pieces.push(json.slice(i, i + FRAME_CHUNK_SIZE));
  if (pieces.length === 0) pieces.push(''); // an empty/falsy payload still needs exactly one (empty) chunk to reassemble correctly
  return pieces.map((chunk, seq) => ({ v: 1, type, userId, reqId, seq, totalChunks: pieces.length, chunk }));
}

/**
 * Sends every frame as its own `sendMessage` call, awaited in order —
 * Telegram doesn't guarantee delivery order across concurrent calls from
 * the same bot, and the receiving side has no way to reorder chunks other
 * than trusting `seq`, so sending one at a time (rather than
 * `Promise.all`) is what actually keeps `seq` meaningful if the receiving
 * side ever needs to fall back to "just concatenate in send order" during
 * debugging.
 *
 * `chatId` accepts a bot's `@username` — verified against Telegram's own
 * sendMessage docs (chat_id: "Unique identifier for the target chat or
 * username of the target channel") and the bot-to-bot page's "send
 * messages to other bots via username if both bots enabled bot-to-bot
 * communication" — before relying on it rather than requiring the
 * harder-to-obtain numeric bot id.
 */
export async function sendFrames(botToken: string, chatId: string, frames: TelegramFrame[]): Promise<void> {
  for (const frame of frames) {
    await sendTelegramMessage(botToken, chatId, JSON.stringify(frame));
  }
}

async function sendTelegramMessage(botToken: string, chatId: string, text: string): Promise<void> {
  const response = await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ chat_id: chatId, text }),
  });
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(`Telegram sendMessage failed: ${response.status} ${body.slice(0, 300)}`);
  }
}

/**
 * Merges one incoming frame into its reassembly buffer (a small DynamoDB
 * item — see chunkBufferKey) and returns the fully-parsed payload once
 * every chunk for that (userId, type, reqId) has arrived, or `undefined`
 * while still waiting on more. Deletes the buffer item the moment it
 * completes, so a retried/duplicate final chunk (Telegram can redeliver a
 * webhook) starts a fresh buffer rather than silently double-processing —
 * callers that need duplicate-delivery protection beyond that (see
 * Telegram's own bot-to-bot loop-prevention warning) should key on `reqId`
 * themselves, e.g. by checking PendingAiRequestItem.status before acting.
 *
 * A single-chunk frame (heartbeats, always; most ai_request/ai_result
 * payloads in practice) still round-trips through this — it just completes
 * immediately, on the very first (and only) call.
 */
export async function assembleFrame(frame: TelegramFrame): Promise<unknown | undefined> {
  const key = chunkBufferKey(frame.userId, frame.type, frame.reqId);

  if (frame.totalChunks === 1) {
    // Common case, and worth short-circuiting: no need to round-trip
    // through DynamoDB at all when there's nothing to reassemble.
    return JSON.parse(frame.chunk || 'null');
  }

  const existing = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: key }));
  const chunks: Record<number, string> = { ...(existing.Item?.chunks as Record<number, string> | undefined) };
  chunks[frame.seq] = frame.chunk;

  const received = Object.keys(chunks).length;
  if (received < frame.totalChunks) {
    await ddb.send(new PutCommand({
      TableName: TABLE_NAME,
      Item: {
        ...key,
        entityType: 'CHUNK_BUFFER',
        chunks,
        totalChunks: frame.totalChunks,
        // 10 minutes — generous relative to the ~2 minute end-to-end
        // timeout the overall Home-mode request is held to
        // (architecture.md §04's sequence diagram), so a stalled buffer
        // never outlives the request it belongs to by much.
        ttl: Math.floor(Date.now() / 1000) + 10 * 60,
      },
    }));
    return undefined;
  }

  await ddb.send(new DeleteCommand({ TableName: TABLE_NAME, Key: key }));
  const joined = Array.from({ length: frame.totalChunks }, (_, i) => chunks[i] ?? '').join('');
  return JSON.parse(joined || 'null');
}

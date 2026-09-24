import { z } from 'zod';
import { createTodoSchema } from './todo-schema';

/**
 * Validates the body of `POST /ai/request` — see ../docs/api/README.md and
 * ../docs/architecture.md §07. A discriminated union on `kind` because plan
 * and chat requests genuinely need different fields; z.discriminatedUnion
 * gives a clean 400 (via lambdas/ai-orchestrator's ZodError handling, same
 * pattern as shared/todo-schema.ts) rather than a pile of "field X only
 * applies when kind=Y" runtime checks.
 *
 * `mode` defaults to 'cloud' and 'home' is accepted here so the shape won't
 * need a breaking change in Phase 3 — lambdas/ai-orchestrator/index.ts is
 * what actually rejects `mode: 'home'` today, not this schema.
 */

const dateSchema = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'expected YYYY-MM-DD');
const modeSchema = z.enum(['cloud', 'home']).default('cloud');

export const planRequestSchema = z.object({
  kind: z.literal('plan'),
  mode: modeSchema,
  scope: z.enum(['day', 'week', 'month', 'year']),
  /** Defaults to today (UTC) — see shared/plan-period.ts. */
  periodStart: dateSchema.optional(),
  /** Optional free-text steer, e.g. "prioritize the client work". */
  focus: z.string().trim().max(500).optional(),
});

export const chatRequestSchema = z.object({
  kind: z.literal('chat'),
  mode: modeSchema,
  subjectType: z.enum(['plan', 'todo', 'general']).default('general'),
  /** A todo id (subjectType='todo') or a Plan's `{scope}#{periodStart}` (subjectType='plan'). */
  subjectId: z.string().max(200).optional(),
  /** Omit to start a new conversation; pass an existing Conversation.id to continue one. */
  conversationId: z.string().uuid().optional(),
  message: z.string().trim().min(1).max(4000),
});

export const aiRequestSchema = z.discriminatedUnion('kind', [planRequestSchema, chatRequestSchema]);

export type PlanRequestInput = z.infer<typeof planRequestSchema>;
export type ChatRequestInput = z.infer<typeof chatRequestSchema>;
export type AiRequestInput = z.infer<typeof aiRequestSchema>;

/**
 * Validates what comes BACK from Bedrock (see lambdas/ai-orchestrator/bedrock.ts's
 * doc comment on why: forced tool-choice reliably returns *a* JSON object,
 * but "matches the shape we asked for" is still worth checking before it's
 * persisted as a Plan/Conversation). Deliberately loose — this is a safety
 * net against a malformed response, not a place to over-constrain a model.
 */
export const planToolOutputSchema = z.object({
  summary: z.string().trim().min(1).max(2000),
  items: z.array(z.object({
    todoId: z.string().min(1),
    rationale: z.string().trim().min(1).max(500),
  })).max(200),
});

/**
 * What the chat model is allowed to specify for a todo it wants to create
 * right now (see shared/ai-finalize.ts's createRequestedTodos, and
 * lambdas/ai-orchestrator/index.ts's buildChatSystemPrompt for when the
 * model is instructed to use this). The exact same field-level validation
 * `createTodoSchema` already applies to a real `POST /todos` body — reused
 * rather than duplicated — minus `reminderAt`/`recurrenceRule` (kept out of
 * v1: a chat-requested todo is a simple ask, "remind me to X" becomes a due
 * date, not a scheduled push reminder or a recurrence rule, at least for
 * now) and `source` (never model-settable — createRequestedTodos always
 * sets it to `'ai'`, the same way `completedAt` is server-derived
 * elsewhere, never client-settable).
 */
const aiCreateTodoSchema = createTodoSchema.omit({ reminderAt: true, recurrenceRule: true, source: true });
export type AiCreateTodoInput = z.infer<typeof aiCreateTodoSchema>;

export const chatToolOutputSchema = z.object({
  reply: z.string().trim().min(1).max(4000),
  /** Zero or more todos to actually create right now — see aiCreateTodoSchema's doc comment. Absent/empty means nothing to create this turn. */
  createTodos: z.array(aiCreateTodoSchema).max(10).optional(),
});

/**
 * JSON Schema mirrors of the two zod schemas above — Bedrock's Converse API
 * (Cloud mode, forced tool-choice) and the Home Bridge Bot's Ollama call
 * (Home mode, via `AiRequestPayload.outputSchema` in shared/telegram.ts)
 * both need the request shape described as JSON Schema going *in*, not just
 * validated coming back out. Kept hand-written and separate from the zod
 * schemas (rather than zod-to-json-schema or similar) since these are
 * intentionally looser/more descriptive (extra `description` fields to
 * steer the model) than the strict parse-time validation above — the same
 * "validate what a model gives back, separately from what you ask it for"
 * split lambdas/ai-orchestrator/bedrock.ts's doc comment describes. Shared
 * here (not left inline in ai-orchestrator) because lambdas/ai-orchestrator
 * (building the request) and lambdas/telegram-webhook (retrying a rejected
 * Home-mode reply with the same schema, ../docs/architecture.md §07) both
 * need the exact same object.
 */
export const PLAN_OUTPUT_JSON_SCHEMA: Record<string, unknown> = {
  type: 'object',
  properties: {
    summary: { type: 'string', description: "1-3 sentence overview of this period's plan" },
    items: {
      type: 'array',
      description: 'Ordered agenda, most important/time-sensitive first',
      items: {
        type: 'object',
        properties: {
          todoId: { type: 'string', description: 'Must be one of the todo ids provided in the input' },
          rationale: { type: 'string', description: 'One short sentence on why/when to do this' },
        },
        required: ['todoId', 'rationale'],
      },
    },
  },
  required: ['summary', 'items'],
};

export const CHAT_OUTPUT_JSON_SCHEMA: Record<string, unknown> = {
  type: 'object',
  properties: {
    reply: { type: 'string', description: 'Your reply, in plain conversational text' },
    createTodos: {
      type: 'array',
      description:
        'Zero or more new todos to actually create right now — only when the user clearly asked you to add, ' +
        "create, or remember a task in this message. Never invent a task the user didn't ask for, and never " +
        'include one just because it was mentioned in passing. Leave this empty for anything else (questions, ' +
        'discussion, planning talk that doesn’t ask for a new task).',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string', description: 'Short task title' },
          notes: { type: 'string', description: 'Optional extra detail' },
          priority: { type: 'string', enum: ['low', 'medium', 'high'], description: 'Defaults to medium if omitted' },
          dueDate: { type: 'string', description: 'YYYY-MM-DD — only if the user specified or clearly implied a date' },
          project: { type: 'string', description: 'Optional project/category label' },
          tags: { type: 'array', items: { type: 'string' }, description: 'Optional short tags' },
        },
        required: ['title'],
      },
    },
  },
  required: ['reply'],
};

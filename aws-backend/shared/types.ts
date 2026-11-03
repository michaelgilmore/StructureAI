/**
 * Shared domain types. Kept dependency-free (no zod, no AWS SDK) so both
 * `infra/` and every `lambdas/*` function can import from here without
 * pulling in unrelated code. See ../docs/architecture.md §06.
 */

export type TodoStatus = 'open' | 'done' | 'archived';
export type TodoPriority = 'low' | 'medium' | 'high';
export type TodoSource = 'app' | 'telegram' | 'ai';

export interface Todo {
  id: string;
  userId: string;
  title: string;
  notes?: string;
  status: TodoStatus;
  priority: TodoPriority;
  /** Calendar date, YYYY-MM-DD. No time zone — see architecture.md if that changes. */
  dueDate?: string | null;
  /** ISO 8601 datetime — when a reminder push should fire. */
  reminderAt?: string | null;
  /** iCal-style RRULE subset, e.g. "FREQ=WEEKLY;INTERVAL=2". See shared/recurrence.ts. */
  recurrenceRule?: string | null;
  /**
   * Set when this todo was created from the Routines screen's "Create
   * task" action (see ../../android-app/README.md's "Routines" section) —
   * the one explicit link in this app between a Todo and anything else.
   * Unlike Goal (deliberately NOT linked to any Todo — see the comment
   * above the Routine section below), a Routine's progress is tracked by
   * an exact count ("3 of 3 this week"), which needs a real reference,
   * not AI inference. Null/absent for an ordinary todo.
   */
  routineId?: string | null;
  project?: string | null;
  tags: string[];
  source: TodoSource;
  createdAt: string;
  updatedAt: string;
  /** Set only when this occurrence was materialized from a recurring template. */
  reminderSentAt?: string;
  /**
   * ISO 8601 datetime — set the moment `status` transitions to `'done'`,
   * cleared the moment it transitions away from `'done'` (reopened).
   * Server-derived only (see lambdas/todo-api/index.ts's `updateTodo`) —
   * there is no client-settable field for this; a client can only cause it
   * to change by changing `status`. Deliberately NOT the same as
   * `updatedAt`, which moves on every edit (title, notes, tags, …)
   * regardless of completion — a completed-todo report needs the date it
   * was actually completed, not the date it was last touched for any
   * reason.
   */
  completedAt?: string | null;
}

/** The DynamoDB item shape for a Todo — the domain type plus table keys. */
export interface TodoItem extends Todo {
  PK: string;
  SK: string;
  entityType: 'TODO';
  /** Sparse GSI1 keys — present only while an open todo has a future reminder. */
  GSI1PK?: 'REMINDER';
  GSI1SK?: string;
}

// ---------------------------------------------------------------------------
// Goals — high-level, longer-running intentions ("get AWS certified"),
// distinct from a Todo (a concrete, schedulable task). Deliberately NOT
// linked to any Todo by a field on either type — see
// ../../android-app/README.md's "Goals" section for why: progress toward
// a goal is judged by the planning AI itself, reading goals and todos
// side by side (lambdas/ai-orchestrator/index.ts's buildSubjectContext,
// 'planning' case), not by an explicit goalId anywhere. Only three
// statuses, unlike Todo's three ('open'/'done'/'archived') — a goal
// doesn't really get "done" the way a task does (there's no single
// completing action), it stays relevant until you archive it.
// ---------------------------------------------------------------------------

export type GoalStatus = 'active' | 'archived';

export interface Goal {
  id: string;
  userId: string;
  title: string;
  notes?: string;
  /** Calendar date, YYYY-MM-DD — an optional target/deadline. Same no-timezone convention as Todo.dueDate. */
  targetDate?: string | null;
  status: GoalStatus;
  createdAt: string;
  updatedAt: string;
}

/** The DynamoDB item shape for a Goal. */
export interface GoalItem extends Goal {
  PK: string;
  SK: string;
  entityType: 'GOAL';
}

// ---------------------------------------------------------------------------
// Routines — things you want to do on a regular cadence ("exercise 3x a
// week"), as opposed to Goals (open-ended, no explicit cadence) or a plain
// Todo (a one-off task). UNLIKE Goal, a Routine IS linked to Todo, via
// Todo.routineId above — "I will create tasks for them from the Routines
// screen" means each created task should count toward that specific
// routine's tally, which needs an exact reference, not fuzzy AI matching
// by title. lambdas/ai-orchestrator/index.ts's buildSubjectContext
// ('planning' case) computes each active routine's `doneCount` for its
// current period straight from Todo.routineId + completedAt, and hands
// the model precise numbers ("2 of 3 this week") rather than asking it to
// infer progress the way it does for Goals.
// ---------------------------------------------------------------------------

export type RoutinePeriod = 'day' | 'week' | 'month';
export type RoutineStatus = 'active' | 'archived';

export interface Routine {
  id: string;
  userId: string;
  title: string;
  notes?: string;
  /** How many times per [period] this routine is meant to happen, e.g. targetCount=3, period='week' for "3x a week". */
  targetCount: number;
  period: RoutinePeriod;
  status: RoutineStatus;
  createdAt: string;
  updatedAt: string;
}

/** The DynamoDB item shape for a Routine. */
export interface RoutineItem extends Routine {
  PK: string;
  SK: string;
  entityType: 'ROUTINE';
}

// ---------------------------------------------------------------------------
// AI (Phase 2 — Bedrock plan generation + dialog). See ../docs/architecture.md
// §03 (Cloud vs Home), §05-§07 (data shapes, the "four scopes"). Cloud mode
// only for now — `mode: 'home'` is accepted by the type so Phase 3 doesn't
// need a breaking change, but lambdas/ai-orchestrator rejects it today.
// ---------------------------------------------------------------------------

export type AiMode = 'cloud' | 'home';
export type AiRequestKind = 'plan' | 'chat';
export type AiRequestStatus = 'sent' | 'done' | 'failed';
export type PlanScope = 'day' | 'week' | 'month' | 'year';
export type ChatSubjectType = 'plan' | 'todo' | 'general' | 'planning';
export type ChatRole = 'user' | 'assistant';

export interface PlanItem {
  todoId: string;
  rationale: string;
}

export interface Plan {
  scope: PlanScope;
  /** YYYY-MM-DD, inclusive. Same no-timezone convention as Todo.dueDate. */
  periodStart: string;
  /** YYYY-MM-DD, inclusive. */
  periodEnd: string;
  summary: string;
  items: PlanItem[];
  engine: AiMode;
  generatedAt: string;
}

/** The DynamoDB item shape for a Plan — PK/SK per architecture.md §05's table. */
export interface PlanRecord extends Plan {
  PK: string;
  SK: string; // PLAN#{scope}#{periodStart}
  entityType: 'PLAN';
  userId: string;
}

export interface ConversationMessage {
  role: ChatRole;
  text: string;
  /** Set on assistant messages only — which engine actually answered. */
  engine?: AiMode;
  ts: string;
}

export interface Conversation {
  id: string;
  subjectType: ChatSubjectType;
  /** A todo id (subjectType='todo') or a Plan's `{scope}#{periodStart}` (subjectType='plan'). Absent for 'general'. */
  subjectId?: string;
  messages: ConversationMessage[];
  updatedAt: string;
}

/** The DynamoDB item shape for a Conversation. */
export interface ConversationRecord extends Conversation {
  PK: string;
  SK: string; // CONVO#{id}
  entityType: 'CONVO';
  userId: string;
}

export interface PendingAiRequest {
  reqId: string;
  kind: AiRequestKind;
  mode: AiMode;
  status: AiRequestStatus;
  /** SK of the Plan or Conversation this request produced/updated, once status is 'done'. */
  resultRef?: string;
  error?: string;
  createdAt: string;
  /** Epoch seconds — DynamoDB TTL attribute (see data-stack.ts). */
  ttl: number;
  /**
   * Home-mode only (Phase 3, ../docs/architecture.md §04) — everything
   * lambdas/telegram-webhook needs to finalize this request once the Home
   * Bridge Bot's reply arrives, without re-deriving it (a plan's
   * candidateIds in particular must stay exactly what the model was shown,
   * not re-queried fresh — see ai-orchestrator's `candidateIds` gating).
   * Absent for mode='cloud', which resolves synchronously and never needs
   * this. See shared/telegram.ts's doc comment for the wire protocol this
   * supports.
   */
  homeContext?: PlanHomeContext | ChatHomeContext;
  /**
   * Home-mode only — the exact system/user prompt (and, for chat, history)
   * sent to the Home Bridge Bot on the first attempt, kept verbatim so a
   * schema-validation retry (../docs/architecture.md §07) can resend the
   * identical content — with only a stricter instruction appended to
   * `system` — rather than re-deriving it from live data, which could
   * drift from whatever `homeContext.candidateIds`/`priorMessages` above
   * were computed against (todos or conversation history can change in the
   * seconds between dispatch and a retry). See lambdas/telegram-webhook's
   * retry logic.
   */
  homePrompt?: {
    system: string;
    userMessage: string;
    history?: { role: 'user' | 'assistant'; text: string }[];
  };
  /**
   * Home-mode only — 0 until the model's first reply fails schema
   * validation; incremented to 1 when telegram-webhook sends the stricter
   * "reply with JSON only" retry (../docs/architecture.md §07). A second
   * failure after that stops retrying — chat falls back to plain text,
   * plan fails outright (see lambdas/telegram-webhook/index.ts).
   */
  homeRetryCount?: number;
}

export interface PlanHomeContext {
  kind: 'plan';
  scope: PlanScope;
  periodStart: string;
  periodEnd: string;
  /** The exact todo ids offered to the model — same gating role as ai-orchestrator's in-memory candidateIds, just serializable. */
  candidateIds: string[];
}

export interface ChatHomeContext {
  kind: 'chat';
  conversationId: string;
  subjectType: ChatSubjectType;
  subjectId?: string;
  /** Prior turns only — NOT including the new user message, which is stored separately below so it's never duplicated if this request is ever retried. */
  priorMessages: ConversationMessage[];
  /** The new user turn this request is answering — appended alongside the assistant reply once the model answers. */
  userMessage: string;
}

/** The DynamoDB item shape for a PendingAiRequest. */
export interface PendingAiRequestItem extends PendingAiRequest {
  PK: string;
  SK: string; // REQ#{reqId}
  entityType: 'PENDING_AI_REQUEST';
  userId: string;
  /**
   * Sparse GSI1 keys (see data-stack.ts's doc comment — GSI1 is shared with
   * reminder-scan's GSI1PK='REMINDER' via a different discriminator value
   * here) — present only while a Home-mode request is genuinely in flight
   * (status='sent'), so lambdas/ai-request-timeout can find "stuck" ones
   * without a table scan. Cleared the same way reminder-scan clears its own
   * GSI1 entry once a request resolves (done/failed) — see that Lambda's
   * `clearReminderIndex` for the precedent.
   */
  GSI1PK?: 'PENDING_HOME_REQUEST';
  GSI1SK?: string; // createdAt — timeout-scan reads oldest-first
}

// ---------------------------------------------------------------------------
// Telegram bridge (Phase 3). See ../docs/architecture.md §04.
// ---------------------------------------------------------------------------

export interface HomeStatus {
  /** ISO 8601 — when the Home Bridge Bot's last heartbeat arrived. */
  lastHeartbeatAt: string;
  /** Whatever model tag the Home Bridge Bot reports loading, e.g. "qwen2.5:7b-instruct". Absent if Ollama itself was unreachable at heartbeat time. */
  model?: string;
  /** Result of the Home Bridge Bot's own local `GET /api/tags` check against Ollama — see architecture.md §04's "Heartbeat" row. False means the bridge process is up (it got far enough to heartbeat) but Ollama itself isn't answering. */
  ollamaOk: boolean;
}

/** The DynamoDB item shape for HomeStatus — one item per user, overwritten in place (never a growing log — see architecture.md §04's "Record" row). */
export interface HomeStatusItem extends HomeStatus {
  PK: string;
  SK: 'HOME#status';
  entityType: 'HOME_STATUS';
  userId: string;
}

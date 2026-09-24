import { todoKey } from './dynamo';
import type { CreateTodoInput } from './todo-schema';
import type { TodoItem, TodoStatus } from './types';

/**
 * Builds a full DynamoDB Todo item, deriving the sparse GSI1 reminder keys.
 * Originally private to lambdas/todo-api/index.ts (createTodo/updateTodo/
 * materializeNextOccurrence); pulled out here once lambdas/ai-orchestrator
 * needed the exact same item-construction logic for AI-created todos (see
 * shared/ai-finalize.ts's createRequestedTodos) — both callers need the
 * same defaults (priority, tags, source, the always-null `completedAt` a
 * caller may then override — see updateTodo's doc comment in
 * lambdas/todo-api/index.ts) rather than two copies that could drift.
 */
export function buildTodoItem(
  userId: string,
  todoId: string,
  input: CreateTodoInput & { status?: TodoStatus },
  createdAt?: string,
): TodoItem {
  const now = new Date().toISOString();
  const status: TodoStatus = input.status ?? 'open';

  const item: TodoItem = {
    ...todoKey(userId, todoId),
    entityType: 'TODO',
    id: todoId,
    userId,
    title: input.title,
    notes: input.notes,
    status,
    priority: input.priority ?? 'medium',
    dueDate: input.dueDate ?? null,
    reminderAt: input.reminderAt ?? null,
    recurrenceRule: input.recurrenceRule ?? null,
    project: input.project ?? null,
    tags: input.tags ?? [],
    source: input.source ?? 'app',
    createdAt: createdAt ?? now,
    updatedAt: now,
    // Always null here — every caller (a fresh create, a materialized
    // recurrence occurrence, or an AI-created todo) starts uncompleted.
    // lambdas/todo-api/index.ts's updateTodo overwrites this after calling
    // buildTodoItem for an edit, based on the actual status transition —
    // see its doc comment.
    completedAt: null,
  };

  if (item.reminderAt && status === 'open') {
    item.GSI1PK = 'REMINDER';
    item.GSI1SK = item.reminderAt;
  }

  return item;
}

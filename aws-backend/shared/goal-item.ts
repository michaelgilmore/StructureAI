import { goalKey } from './dynamo';
import type { CreateGoalInput } from './goal-schema';
import type { GoalItem, GoalStatus } from './types';

/**
 * Builds a full DynamoDB Goal item — the same role shared/todo-item.ts's
 * buildTodoItem plays for Todo, kept as a separate small function (not a
 * generic builder) because the two item shapes genuinely differ (no
 * priority/tags/recurrence/GSI1 reminder keys here).
 */
export function buildGoalItem(
  userId: string,
  goalId: string,
  input: CreateGoalInput & { status?: GoalStatus },
  createdAt?: string,
): GoalItem {
  const now = new Date().toISOString();
  return {
    ...goalKey(userId, goalId),
    entityType: 'GOAL',
    id: goalId,
    userId,
    title: input.title,
    notes: input.notes,
    targetDate: input.targetDate ?? null,
    status: input.status ?? 'active',
    createdAt: createdAt ?? now,
    updatedAt: now,
  };
}

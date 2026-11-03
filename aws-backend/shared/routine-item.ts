import { routineKey } from './dynamo';
import type { CreateRoutineInput } from './routine-schema';
import type { RoutineItem, RoutineStatus } from './types';

/** Mirrors shared/goal-item.ts's buildGoalItem (see that file) — for Routine instead. */
export function buildRoutineItem(
  userId: string,
  routineId: string,
  input: CreateRoutineInput & { status?: RoutineStatus },
  createdAt?: string,
): RoutineItem {
  const now = new Date().toISOString();

  return {
    ...routineKey(userId, routineId),
    entityType: 'ROUTINE',
    id: routineId,
    userId,
    title: input.title,
    notes: input.notes,
    targetCount: input.targetCount,
    period: input.period,
    status: input.status ?? 'active',
    createdAt: createdAt ?? now,
    updatedAt: now,
  };
}

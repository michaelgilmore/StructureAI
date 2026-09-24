import { z } from 'zod';
import { RECURRENCE_RULE_PATTERN } from './recurrence';

const dueDatePattern = /^\d{4}-\d{2}-\d{2}$/;

export const createTodoSchema = z.object({
  title: z.string().trim().min(1).max(200),
  notes: z.string().max(4000).optional(),
  priority: z.enum(['low', 'medium', 'high']).default('medium'),
  dueDate: z.string().regex(dueDatePattern, 'expected YYYY-MM-DD').nullable().optional(),
  reminderAt: z.string().datetime().nullable().optional(),
  recurrenceRule: z.string().regex(RECURRENCE_RULE_PATTERN, 'expected FREQ=DAILY|WEEKLY|MONTHLY|YEARLY[;INTERVAL=n]').nullable().optional(),
  project: z.string().max(100).nullable().optional(),
  tags: z.array(z.string().max(40)).max(20).default([]),
  source: z.enum(['app', 'telegram', 'ai']).default('app'),
});

export const updateTodoSchema = createTodoSchema.partial().extend({
  status: z.enum(['open', 'done', 'archived']).optional(),
});

export type CreateTodoInput = z.infer<typeof createTodoSchema>;
export type UpdateTodoInput = z.infer<typeof updateTodoSchema>;

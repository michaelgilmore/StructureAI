import { z } from 'zod';

const dateSchema = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'expected YYYY-MM-DD');

export const createGoalSchema = z.object({
  title: z.string().trim().min(1).max(200),
  notes: z.string().max(2000).optional(),
  /** Optional target/deadline date — a goal doesn't need one, unlike how a Todo's dueDate drives most of this app's scheduling. */
  targetDate: dateSchema.nullable().optional(),
});

export const updateGoalSchema = createGoalSchema.partial().extend({
  status: z.enum(['active', 'archived']).optional(),
});

export type CreateGoalInput = z.infer<typeof createGoalSchema>;
export type UpdateGoalInput = z.infer<typeof updateGoalSchema>;

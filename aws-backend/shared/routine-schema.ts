import { z } from 'zod';

export const createRoutineSchema = z.object({
  title: z.string().trim().min(1).max(200),
  notes: z.string().max(2000).optional(),
  targetCount: z.number().int().min(1).max(365),
  period: z.enum(['day', 'week', 'month']),
});

export const updateRoutineSchema = createRoutineSchema.partial().extend({
  status: z.enum(['active', 'archived']).optional(),
});

export type CreateRoutineInput = z.infer<typeof createRoutineSchema>;
export type UpdateRoutineInput = z.infer<typeof updateRoutineSchema>;

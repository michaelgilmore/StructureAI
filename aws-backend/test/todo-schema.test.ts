import { createTodoSchema, updateTodoSchema } from '../shared/todo-schema';

describe('createTodoSchema', () => {
  it('accepts a minimal todo and fills in defaults', () => {
    const parsed = createTodoSchema.parse({ title: 'Buy milk' });
    expect(parsed).toMatchObject({
      title: 'Buy milk',
      priority: 'medium',
      tags: [],
      source: 'app',
    });
  });

  it('rejects an empty title', () => {
    expect(() => createTodoSchema.parse({ title: '' })).toThrow();
  });

  it('rejects a title over 200 characters', () => {
    expect(() => createTodoSchema.parse({ title: 'x'.repeat(201) })).toThrow();
  });

  it('rejects a malformed due date', () => {
    expect(() => createTodoSchema.parse({ title: 'x', dueDate: '09/01/2026' })).toThrow();
  });

  it('accepts a well-formed due date and recurrence rule together', () => {
    const parsed = createTodoSchema.parse({
      title: 'Water the plants',
      dueDate: '2026-09-01',
      recurrenceRule: 'FREQ=WEEKLY;INTERVAL=1',
    });
    expect(parsed.dueDate).toBe('2026-09-01');
    expect(parsed.recurrenceRule).toBe('FREQ=WEEKLY;INTERVAL=1');
  });

  it('rejects a recurrence rule outside the supported subset', () => {
    expect(() =>
      createTodoSchema.parse({ title: 'x', recurrenceRule: 'FREQ=WEEKLY;BYDAY=MO' }),
    ).toThrow();
  });

  it('rejects more than 20 tags', () => {
    const tags = Array.from({ length: 21 }, (_, i) => `tag-${i}`);
    expect(() => createTodoSchema.parse({ title: 'x', tags })).toThrow();
  });
});

describe('updateTodoSchema', () => {
  it('accepts a status-only patch', () => {
    expect(updateTodoSchema.parse({ status: 'done' })).toEqual({ status: 'done' });
  });

  it('accepts an empty patch', () => {
    expect(updateTodoSchema.parse({})).toEqual({});
  });

  it('rejects an invalid status', () => {
    expect(() => updateTodoSchema.parse({ status: 'archivedd' })).toThrow();
  });
});

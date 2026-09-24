import { nextOccurrenceDueDate, parseRecurrenceRule, shiftReminder } from '../shared/recurrence';

describe('parseRecurrenceRule', () => {
  it('parses a bare frequency as interval 1', () => {
    expect(parseRecurrenceRule('FREQ=WEEKLY')).toEqual({ freq: 'WEEKLY', interval: 1 });
  });

  it('parses an explicit interval', () => {
    expect(parseRecurrenceRule('FREQ=MONTHLY;INTERVAL=3')).toEqual({ freq: 'MONTHLY', interval: 3 });
  });

  it('rejects anything outside the supported subset', () => {
    expect(parseRecurrenceRule('FREQ=WEEKLY;BYDAY=MO')).toBeNull();
    expect(parseRecurrenceRule('not a rule')).toBeNull();
    expect(parseRecurrenceRule('FREQ=HOURLY')).toBeNull();
  });
});

describe('nextOccurrenceDueDate', () => {
  it('advances daily', () => {
    expect(nextOccurrenceDueDate('2026-09-01', 'FREQ=DAILY')).toBe('2026-09-02');
  });

  it('advances weekly with an interval', () => {
    expect(nextOccurrenceDueDate('2026-09-01', 'FREQ=WEEKLY;INTERVAL=2')).toBe('2026-09-15');
  });

  it('advances monthly across a year boundary', () => {
    expect(nextOccurrenceDueDate('2026-12-15', 'FREQ=MONTHLY')).toBe('2027-01-15');
  });

  it('advances yearly, including on a leap-day due date', () => {
    expect(nextOccurrenceDueDate('2028-02-29', 'FREQ=YEARLY')).toBe('2029-03-01');
  });

  it('returns null for an unparseable rule', () => {
    expect(nextOccurrenceDueDate('2026-09-01', 'garbage')).toBeNull();
  });
});

describe('shiftReminder', () => {
  it('preserves the offset between the reminder and the due date', () => {
    // reminder is 1 day (24h) before the due date
    const shifted = shiftReminder('2026-09-01', '2026-08-31T09:00:00.000Z', '2026-09-08');
    expect(shifted).toBe('2026-09-07T09:00:00.000Z');
  });

  it('returns null when there was no reminder to begin with', () => {
    expect(shiftReminder('2026-09-01', null, '2026-09-08')).toBeNull();
    expect(shiftReminder('2026-09-01', undefined, '2026-09-08')).toBeNull();
  });
});

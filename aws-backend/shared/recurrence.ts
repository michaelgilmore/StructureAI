/**
 * A deliberately small subset of iCal RRULE (RFC 5545): FREQ + optional
 * INTERVAL, nothing else. That's what a personal todo app's "repeat every
 * N days/weeks/months/years" needs — see the "Recurrence model" note in
 * ../docs/architecture.md §06 for why a full RRULE parser isn't worth it here.
 */

export type RecurrenceFreq = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

export interface ParsedRecurrence {
  freq: RecurrenceFreq;
  interval: number;
}

export const RECURRENCE_RULE_PATTERN = /^FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;INTERVAL=([1-9][0-9]?))?$/;

export function parseRecurrenceRule(rule: string): ParsedRecurrence | null {
  const match = RECURRENCE_RULE_PATTERN.exec(rule);
  if (!match) return null;
  return {
    freq: match[1] as RecurrenceFreq,
    interval: match[3] ? parseInt(match[3], 10) : 1,
  };
}

/**
 * Given a template's due date (YYYY-MM-DD) and its recurrence rule, returns
 * the next occurrence's due date. Returns null for an unparseable rule.
 */
export function nextOccurrenceDueDate(dueDate: string, rule: string): string | null {
  const parsed = parseRecurrenceRule(rule);
  if (!parsed) return null;

  const d = new Date(`${dueDate}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return null;

  switch (parsed.freq) {
    case 'DAILY':
      d.setUTCDate(d.getUTCDate() + parsed.interval);
      break;
    case 'WEEKLY':
      d.setUTCDate(d.getUTCDate() + 7 * parsed.interval);
      break;
    case 'MONTHLY':
      d.setUTCMonth(d.getUTCMonth() + parsed.interval);
      break;
    case 'YEARLY':
      d.setUTCFullYear(d.getUTCFullYear() + parsed.interval);
      break;
  }
  return d.toISOString().slice(0, 10);
}

/**
 * Reminders are stored as an absolute timestamp, so moving the due date
 * forward has to shift the reminder by the same offset (e.g. "the day
 * before, at 9am" has to stay "the day before, at 9am" on the new date).
 */
export function shiftReminder(
  oldDueDate: string,
  oldReminderAt: string | null | undefined,
  newDueDate: string,
): string | null {
  if (!oldReminderAt) return null;
  const oldDueMs = new Date(`${oldDueDate}T00:00:00Z`).getTime();
  const oldReminderMs = new Date(oldReminderAt).getTime();
  if (Number.isNaN(oldDueMs) || Number.isNaN(oldReminderMs)) return null;

  const offsetMs = oldDueMs - oldReminderMs;
  const newDueMs = new Date(`${newDueDate}T00:00:00Z`).getTime();
  return new Date(newDueMs - offsetMs).toISOString();
}

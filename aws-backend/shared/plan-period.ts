import type { PlanScope } from './types';

/**
 * Turns a plan `scope` + optional `periodStart` into a concrete
 * [periodStart, periodEnd] date range (both YYYY-MM-DD, inclusive, UTC —
 * same no-timezone convention as Todo.dueDate, see shared/recurrence.ts for
 * the sibling date-math helpers on that convention). `periodStart` defaults
 * to "today" in UTC when the caller doesn't supply one; day/week/month/year
 * scopes each anchor their window differently:
 *
 * - day:   periodStart itself, one day.
 * - week:  a plain 7-day window starting at periodStart — deliberately not
 *          ISO-week-aligned (Monday-start), to avoid a "why does my Tuesday
 *          plan only cover 4 days" surprise when periodStart isn't a Monday.
 * - month: periodStart's calendar month, from the 1st to the last day.
 * - year:  periodStart's calendar year, Jan 1 to Dec 31.
 */
export function resolvePeriod(scope: PlanScope, periodStart?: string): { periodStart: string; periodEnd: string } {
  const start = periodStart ?? todayUtc();
  const startDate = new Date(`${start}T00:00:00Z`);
  if (Number.isNaN(startDate.getTime())) {
    throw new Error(`invalid periodStart: ${start}`);
  }

  switch (scope) {
    case 'day':
      return { periodStart: start, periodEnd: start };
    case 'week': {
      const end = new Date(startDate);
      end.setUTCDate(end.getUTCDate() + 6);
      return { periodStart: start, periodEnd: toDateString(end) };
    }
    case 'month': {
      const monthStart = new Date(Date.UTC(startDate.getUTCFullYear(), startDate.getUTCMonth(), 1));
      const monthEnd = new Date(Date.UTC(startDate.getUTCFullYear(), startDate.getUTCMonth() + 1, 0));
      return { periodStart: toDateString(monthStart), periodEnd: toDateString(monthEnd) };
    }
    case 'year': {
      const yearStart = new Date(Date.UTC(startDate.getUTCFullYear(), 0, 1));
      const yearEnd = new Date(Date.UTC(startDate.getUTCFullYear(), 11, 31));
      return { periodStart: toDateString(yearStart), periodEnd: toDateString(yearEnd) };
    }
  }
}

function todayUtc(): string {
  return toDateString(new Date());
}

function toDateString(d: Date): string {
  return d.toISOString().slice(0, 10);
}

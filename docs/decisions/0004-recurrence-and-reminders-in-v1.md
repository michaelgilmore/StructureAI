# 0004 — Recurring todos and due-date reminders ship in v1

## Context

Recurrence and due-date notifications are common "we'll get to it later"
features, but they touch the data model (`Todo.reminderAt`,
`Todo.recurrenceRule`) closely enough that deferring them to a later phase
would mean revisiting the schema after other features are already built on it.

## Decision

Both ship in v1: Phase 0 adds the schema fields and an EventBridge Scheduler
→ Lambda scan for due todos; Phase 1 adds the Android UI (recurrence and
reminder pickers) and FCM push handling.

## Consequences

- Requires one genuinely new piece of AWS infrastructure — EventBridge
  Scheduler — earlier than would otherwise be needed (see `architecture.md` §05).
- Reminders are server-driven (a scheduled scan pushes via FCM), not phone
  alarms, so a reminder still fires even if the app has been closed for a
  week — but it does mean reminders don't work at all until Phase 0's scan
  is deployed, even though the phone UI for setting them exists from Phase 1.
- A recurring todo is modeled as a template that materializes its next
  occurrence as its own concrete `Todo` item on completion — see the
  "Recurrence model" note in `architecture.md` §06 for why (independently
  completable occurrences, no lost history).

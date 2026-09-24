# 0002 — AWS is the source of truth for todos

## Context

Todos can be created or edited from the Android app, from Telegram (via the
AI dialog), or, eventually, from the AI planner itself. Something has to own
the canonical state.

## Decision

The AWS backend (DynamoDB, behind the API) is cloud-first and owns every
todo. The Android app and the Telegram bridge are both clients of the same
API — a todo added from either place shows up in both. The phone's local
Room database is an offline queue and read cache, not a second source of
truth.

## Consequences

- Requires the app to handle "write while offline, sync later" instead of
  assuming its local state is authoritative.
- Makes the Telegram-added-todo → app-sees-it flow trivial: it's the same
  write path, no special-cased sync logic.
- Means the app is not usable at all, for anything beyond viewing a stale
  cache, without connectivity to AWS — accepted, since a personal todo app
  living entirely on a phone with no backup was the alternative being ruled out.

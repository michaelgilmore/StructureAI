# 0001 — AI mode is a toggle, not an automatic hybrid

## Context

The app can answer AI requests from Amazon Bedrock (cloud) or from a
self-hosted Ollama model reached over Telegram (home). Those two paths have
very different latency, cost, and reliability characteristics. The system
needed a rule for which one answers a given request.

## Decision

AI mode is a single Settings switch: **Cloud** or **Home**. Whichever is
selected answers every plan generation and every chat turn until the user
changes it. There is no automatic per-message hybrid and no silent fallback
from Home to Cloud mid-conversation — a failed Home request surfaces a clear
error with a one-tap "switch to Cloud and retry."

## Consequences

- Predictable: the user always knows which model answered, and cost never
  spikes from an unnoticed silent fallback to a paid model.
- Both paths are built behind a common `AiEngine` contract, so the app and
  prompt logic don't need to know which engine is live — this keeps a future
  *opt-in* "auto" mode (try Home, fall back to Cloud after a timeout)
  possible without it being the default now.
- A genuinely offline home box means Home mode is simply unavailable until
  switched — by design, not by bug.

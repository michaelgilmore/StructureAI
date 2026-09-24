# home-status

Phase 3 (Telegram bridge) — see
[`../../../docs/architecture.md` §04](../../../docs/architecture.md#04--the-telegram--ollama-bridge),
"Knowing whether Home is actually up". `GET /home/status`,
Cognito-authenticated. A plain read of the `HomeStatus` item
`../telegram-webhook/`'s heartbeat handler keeps up to date — no Telegram
round trip on this path, which is the whole point (the app can show a
status dot without waiting on your home machine to answer anything).

Adds no new infrastructure beyond the one Lambda + API route: same table,
same `HomeStatus` item shape `../telegram-webhook/` already writes.

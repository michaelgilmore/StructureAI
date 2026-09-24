# telegram-webhook

Phase 3 (Telegram bridge) — see
[`../../../docs/architecture.md` §04](../../../docs/architecture.md#04--the-telegram--ollama-bridge).
`POST /telegram/webhook`, **no Cognito authorizer** — this is Telegram
calling us, not the app. Authenticity comes from the
`X-Telegram-Bot-Api-Secret-Token` header instead (set once via `setWebhook`,
see the root README's "Telegram bridge" setup section).

Reassembles the chunked frames `../../shared/telegram.ts` defines, records
the Home Bridge Bot's heartbeats into `HomeStatus`, and finishes a Home-mode
plan/chat request once its `ai_result` frame arrives — reusing the exact
same validation/persistence logic (`../../shared/ai-finalize.ts`) the Cloud
path in `../ai-orchestrator/` uses, plus the one-retry-then-fallback
behavior architecture.md §07 describes for a reply that fails schema
validation (plan fails outright after the retry; chat falls back to showing
the raw reply as plain text).

Depends on `../ai-orchestrator/`'s `PendingAiRequestItem.homePrompt` and
`.homeContext` fields — a Home-mode request dispatched by an older
deployment that predates those fields won't have them to finalize against.
Not a concern in practice: both ship in this same round.

**Unverified against real Telegram bot-to-bot traffic** — see
`../../shared/telegram.ts`'s doc comment. The exact shape of a bot-to-bot
`message` Update (in particular, whether `message.from.username` is
reliably present the way a human sender's would be) is asserted here from
Telegram's own docs, not from a real test. Watch CloudWatch Logs on this
Lambda the first time you actually exchange messages between the two bots.

See [`../../../docs/architecture.md` §04](../../../docs/architecture.md#04--the-telegram--ollama-bridge).

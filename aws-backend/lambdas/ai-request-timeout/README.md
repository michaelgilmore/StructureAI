# ai-request-timeout

Phase 3 (Telegram bridge) — see
[`../../../docs/architecture.md` §04](../../../docs/architecture.md#04--the-telegram--ollama-bridge),
the sequence diagram's "No reply within ~2 min → status=failed" note.
EventBridge-scheduled (every ~1 minute — see `../../infra/lib/telegram-stack.ts`),
same "scan a sparse GSI1 discriminator, act, clear the index" shape as
`../reminder-scan/`, just for stuck Home-mode requests instead of due
reminders.

Guards against a real race with `../telegram-webhook/` (a reply arriving in
the same window this Lambda is scanning) with a conditional update rather
than an unconditional overwrite — see the doc comment in `index.ts` on
`failIfStillPending`. Losing that race is normal and silent, not logged as
an error.

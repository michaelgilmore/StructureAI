# Decisions

Short records of decisions that were genuinely debated, in the order they
were made. Not a changelog and not a design doc — `architecture.md` is the
current state; these are the "why," kept so a future rewrite has to
consciously overturn a decision rather than just forget it existed.

Format: Context (what was in tension) → Decision (what was chosen) →
Consequences (what that commits us to). Add a new file rather than editing
an old one when a decision changes — number sequentially.

| # | Decision |
|---|---|
| [0001](0001-ai-mode-is-a-toggle-not-a-hybrid.md) | AI mode is a user-facing toggle, not an automatic hybrid |
| [0002](0002-cloud-first-source-of-truth.md) | AWS is the source of truth for todos, not the phone |
| [0003](0003-telegram-bot-to-bot-bridge.md) | Reach home Ollama via two Telegram bots, not a VPN or tunnel |
| [0004](0004-recurrence-and-reminders-in-v1.md) | Recurring todos and due-date reminders ship in v1 |

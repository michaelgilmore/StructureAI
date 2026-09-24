# home-bridge-bot

The one piece of this project that doesn't run on AWS or on the phone — a
small always-on process meant to run at home, next to Ollama (a Raspberry
Pi, a home server, or the Ollama box itself). See
[`../docs/architecture.md` §04](../docs/architecture.md#04--the-telegram--ollama-bridge).

What it does:

- Long-polls its own Telegram bot for messages (outbound connection only —
  never accepts anything inbound).
- On a prompt from the Cloud Relay Bot: calls local Ollama
  (`POST http://localhost:11434/api/chat`, or whatever host:port is
  configured) and DMs the completion back.
- Every ~90 seconds: pings local Ollama (`/api/tags`) and DMs a heartbeat
  so the app's status dot stays current — no app-side polling of Telegram needed.

Configuration (once built) will live in a local `.env` — bot token, Ollama
host, and the model tag — never committed. Swapping the model (e.g. from an
8B model to something larger once the hardware's confirmed) is meant to be a
one-line config change here, not a code or infra change.

Phase 3. Empty for now. Language/runtime not yet decided — Python
(`python-telegram-bot` / `aiogram`) and Node (`grammY`) are both reasonable;
pick whichever is more comfortable to run unattended on the target hardware.

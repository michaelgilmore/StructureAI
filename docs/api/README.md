# API contract

Empty until Phase 0. Once the Todo CRUD API exists, its OpenAPI spec
(`openapi.yaml`) belongs here, generated from or kept next to the
`aws-backend/infra` CDK definitions so the contract can't silently drift
from the deployed API.

Endpoints anticipated by `../architecture.md` (§04–§06), for reference
until the spec exists:

- `GET/POST/PATCH/DELETE /todos` — Todo CRUD
- `POST /device/token` — register this device's FCM token for push
  reminders (Cognito-authenticated, one device per user). Implemented — see
  `../../aws-backend/lambdas/device-token/README.md`.
- `POST /ai/request` / `GET /ai/request/{reqId}` — plan generation + dialog,
  Cloud or Home mode. Implemented — see
  `../../aws-backend/lambdas/ai-orchestrator/README.md` and the contract
  below. Home mode's *backend* dispatch is implemented and deployable; it
  won't actually complete a request end-to-end until a Home Bridge Bot
  exists to answer it (Phase 3's other half, not yet built — see
  `../../aws-backend/README.md`'s "Telegram bridge" section).
- `GET /home/status` — Home Ollama presence (last heartbeat, model).
  Implemented — see `../../aws-backend/lambdas/home-status/README.md` and
  the contract below.
- `POST /telegram/webhook` — Telegram → Cloud Relay Bot (internal, not for
  the app). Implemented — see
  `../../aws-backend/lambdas/telegram-webhook/README.md` and the contract
  below.

## `POST /ai/request`

Cognito-authenticated, same as `/todos`. Behavior depends on `mode`:

- **`mode: "cloud"`** runs synchronously — Bedrock's own reply is typically
  1-4s (see `../architecture.md` §03), so the response below normally comes
  back on this same call rather than needing a poll.
- **`mode: "home"`** is genuinely async (see `../architecture.md` §04): this
  call only dispatches the request to the Home Bridge Bot over Telegram and
  returns immediately with `{"reqId", "status": "sent", "kind"}` — no
  `result` yet. Poll `GET /ai/request/{reqId}` (below) until `status`
  becomes `"done"` or `"failed"`. A Home-mode request that gets no reply
  within ~2 minutes is marked `"failed"` automatically (see
  `../../aws-backend/lambdas/ai-request-timeout/README.md`) and also
  triggers the same push notification a completed request does, so a
  poll isn't strictly necessary — the push is what tells you it's worth
  checking.

Request body — one of two shapes depending on `kind`:

```jsonc
// kind: "plan"
{
  "kind": "plan",
  "mode": "cloud",                // or "home" — see above
  "scope": "day",                // "day" | "week" | "month" | "year"
  "periodStart": "2026-09-08",   // optional, YYYY-MM-DD, defaults to today (UTC)
  "focus": "prioritize the client work"  // optional free text
}

// kind: "chat"
{
  "kind": "chat",
  "mode": "cloud",                // or "home"
  "subjectType": "todo",         // "plan" | "todo" | "general", defaults to "general"
  "subjectId": "<todo id>",      // required for "todo"; for "plan", a Plan's "{scope}#{periodStart}", e.g. "week#2026-09-08"
  "conversationId": "<uuid>",    // omit to start a new conversation; pass an existing one's id to continue it
  "message": "what should I do first?"
}
```

Response (200) — Cloud mode, or a Home-mode dispatch failure caught
synchronously (e.g. the Home Bridge Bot's Telegram credentials are
misconfigured): `result`'s shape depends on `kind`. Home mode's normal case
is `{"reqId", "status": "sent", "kind"}` with no `result` — see above.

```jsonc
// kind: "plan" — the stored Plan record (see shared/types.ts's Plan)
{
  "reqId": "...", "status": "done", "kind": "plan",
  "result": {
    "scope": "day", "periodStart": "2026-09-08", "periodEnd": "2026-09-08",
    "summary": "...", "items": [{ "todoId": "...", "rationale": "..." }],
    "engine": "cloud", "generatedAt": "..."
  }
}

// kind: "chat" — condensed, not the full Conversation record
{
  "reqId": "...", "status": "done", "kind": "chat",
  "result": {
    "conversationId": "...", "subjectType": "todo", "subjectId": "...",
    "reply": "...",
    "createdTodos": [ /* optional — see below; omitted/empty on most replies */ ]
  }
}
```

A request that fails (Bedrock error, bad `subjectId`/`conversationId`
reference) responds with a non-2xx and `{"reqId", "status": "failed",
"error"}` — the same shape `GET /ai/request/{reqId}` reports for a request
that later ends up `failed`.

### Chat can create todos

If the user's message clearly asks the assistant to add, create, or
remember a task, the model may create one or more real todos as part of
answering — not a suggestion the app has to separately confirm, an actual
`POST /todos`-equivalent write (`source: "ai"`, same validation as a normal
todo create minus `reminderAt`/`recurrenceRule` — see
`../../aws-backend/shared/ai-schema.ts`'s `aiCreateTodoSchema`). The model
still always returns `reply` as well, so the conversation reads naturally
either way.

- **Cloud mode**: `result.createdTodos` (shown above) carries the full
  created `Todo` record(s) — same shape `GET /todos` returns — so the app
  can show and navigate to them immediately without a separate fetch.
  Omitted (not just empty) when nothing was created this turn, so existing
  clients that don't read this field see no change.
- **Home mode**: the todo(s) are created the same way, but this response
  has no synchronous channel to report them through (`telegram-webhook`
  only ever replies to Telegram's own webhook call, not to the app) — the
  model's `reply` text and the app's next `GET /todos` sync are how the
  user finds out what got created.

## `GET /ai/request/{reqId}`

```jsonc
{ "reqId": "...", "status": "sent" }                              // Cloud: rare, mid-flight. Home: the normal state until it resolves.
{ "reqId": "...", "status": "failed", "kind": "...", "error": "..." }
{ "reqId": "...", "status": "done", "kind": "plan", "result": { /* the stored Plan record */ } }
{ "reqId": "...", "status": "done", "kind": "chat", "result": { /* the stored Conversation record, incl. full messages[] — not the condensed POST shape */ } }
```

A `"done"` Plan/Conversation record's `engine` field is `"cloud"` or
`"home"` depending on which one actually answered — see
`../architecture.md` §06.

## `GET /home/status`

Cognito-authenticated. A plain DynamoDB read (see
`../../aws-backend/lambdas/home-status/README.md`) — no Telegram round
trip, so this is safe to poll from the app on a normal interval (e.g. when
Settings is open) without waking anything at your house.

```jsonc
// No heartbeat ever recorded (Home Bridge Bot never run, or not deployed yet)
{ "online": false }

// A heartbeat exists
{
  "online": true,                 // recent heartbeat AND ollamaOk — see below
  "lastHeartbeatAt": "2026-09-13T21:40:00.000Z",
  "model": "qwen2.5:7b-instruct", // whatever the Home Bridge Bot reports Ollama has loaded; absent if Ollama was unreachable
  "ollamaOk": true                // false = the Home Bridge Bot process is up but Ollama itself didn't answer
}
```

`online` requires both a heartbeat within the last ~4.5 minutes (3 missed
~90s heartbeats' worth of slack) AND `ollamaOk: true` — a bridge that's
reachable but reports Ollama down isn't actually usable for a Home-mode
request, so it's not reported as online.

## `POST /telegram/webhook`

**Not for the app** — this is Telegram's own webhook target, hit by
Telegram's servers when the Cloud Relay Bot receives a message. **No
Cognito authorizer.** Authenticity comes from the
`X-Telegram-Bot-Api-Secret-Token` header instead, checked against a secret
you set once via `setWebhook` — see
`../../aws-backend/README.md`'s "Telegram bridge" section for the exact
setup steps, and `../../aws-backend/lambdas/telegram-webhook/README.md` for
what this handler actually does with an incoming update.

Always responds `200 {"ok": true}` to anything it recognizes and
intentionally ignores (a message from an unexpected sender, an update with
no text) — Telegram retries delivery on anything else, so a genuine
processing failure responds `500` on purpose, to get that retry. A request
missing or failing the secret-token check gets `401`.

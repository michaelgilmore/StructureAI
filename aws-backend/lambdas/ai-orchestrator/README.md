# ai-orchestrator

`POST /ai/request` and `GET /ai/request/{reqId}` — plan generation across
all four scopes (day, week, month, year) and dialog grounded in a todo, a
generated plan, or "just my todos in general", in either Cloud or Home
mode. See `index.ts` for the full logic and `../../../docs/api/README.md`
for the request/response contract.

**Cloud mode** (Phase 2 — deployed and confirmed working end to end, see
`../../README.md`) calls Bedrock through `bedrock.ts`'s `callBedrockTool`,
which forces a specific tool via the Converse API's `toolChoice` so the
model's reply is already the structured JSON shape being asked for — see
that file's doc comment for exactly what's verified vs. assumed about that
API.

**Home mode** (Phase 3 — this Lambda's dispatch half is built this round,
not yet deployed/tested; see `../../README.md`'s "Telegram bridge"
section) builds the exact same prompt/schema Cloud mode would have handed
Bedrock (`buildPlanPrompt` / `buildChatSystemPrompt`, shared by both paths)
and instead sends it to the Home Bridge Bot as chunked Telegram frames
(`../../shared/telegram.ts`), returning immediately with
`status: "sent"`. It never touches Bedrock. Finishing a Home-mode request —
validating the reply and persisting a Plan/Conversation record — happens
later, in `../telegram-webhook/`, once that bot's `ai_result` frame arrives;
both paths share the same validate-then-persist logic
(`../../shared/ai-finalize.ts`'s `finalizePlanResult` / `validateChatReply`
/ etc.) rather than duplicating it, so Cloud mode's already-working
behavior is unchanged by this refactor — see `git log`/the diff on
`index.ts` if you want to confirm that by hand.

Either way, the returned/stored shape is re-validated with zod
(`shared/ai-schema.ts`'s `planToolOutputSchema` / `chatToolOutputSchema`,
via `shared/ai-finalize.ts`) before persisting anything — belt and
suspenders, per `../../../docs/architecture.md` §03's "the orchestrator
validates every response against the schema," and on the Home path the
thing that triggers `../telegram-webhook/`'s one-retry-then-fallback
behavior (§07) on a bad reply.

The `PendingAiRequest` item this Lambda writes on every call — including a
Cloud-mode call, which answers synchronously — is what makes the async
Home-mode path possible without a second item shape; see `index.ts`'s top
doc comment.

**Chat can create todos.** `buildChatSystemPrompt` tells the model it may
add `createTodos` (zero or more, `shared/ai-schema.ts`'s
`aiCreateTodoSchema` — the same field-level validation `POST /todos` uses,
minus `reminderAt`/`recurrenceRule`/`source`) alongside its `reply`, when
the user clearly asked it to add/create/remember a task — this is the same
forced-tool-choice JSON response, just a richer schema, not a second model
call. `shared/ai-finalize.ts`'s `createRequestedTodos` actually writes them
(`source: "ai"`) and both `runChat` here (Cloud) and
`../telegram-webhook/index.ts`'s `handleAiResult` (Home) call it — Cloud
mode merges the created `Todo` record(s) into its response as
`createdTodos` (see `../../../docs/api/README.md`'s "Chat can create
todos"); Home mode calls it for the side effect only, since
`telegram-webhook` has no synchronous channel back to the app.

**Planning mode plays a more active role, not just a stricter read of
`kind: "plan"`.** `subjectType: "planning"` (`buildSubjectContext`'s
`'planning'` case) is a genuinely conversational replacement for the old
one-shot plan generation: it asks the user how many days they're planning
for before doing anything else, then builds its context from *every* open
todo (`queryTodos` filtered only to `status === 'open'`, deliberately not
`buildPlanPrompt`'s stricter "has a due date within the period" filter — an
undated open todo is exactly the kind of thing a one-shot plan silently
skipped, and the whole point here is reviewing everything). That context
also includes `notes` (truncated to 300 chars via the new `truncate`
helper) and `tags` — fields no other AI-facing context in this codebase
sends — since there's no explicit "depends on" field on a Todo; any
dependency the model reasons about has to come from that free-text/metadata
inference. `buildChatSystemPrompt` instructs the model to use
`suggestedDueDateChanges` (`shared/ai-schema.ts`'s `dueDateChangeSchema`)
for that — e.g. two related todos or an inferred dependency whose current
due dates don't line up — rather than just repeating the due dates already
on file. Same forced-tool-choice single model call as everything else here,
just a richer schema again.

`suggestedDueDateChanges` is deliberately **never auto-applied**, unlike
`createTodos` above — `shared/ai-finalize.ts`'s `filterValidDueDateChanges`
only drops suggestions referencing a `todoId` outside that turn's candidate
set (the same id-gating `finalizePlanResult` already does for plan items);
it does not write anything. Applying an accepted suggestion goes through
the pre-existing `PATCH /todos/{id}` (`../todo-api/index.ts`'s
`updateTodo`) the same as any other due-date edit — no new write path was
added for this. See `../../../docs/api/README.md`'s "Planning mode" section
for the wire contract, and `../../../android-app/README.md` for the
Android-side accept flow. `candidateIds` (needed for that gating) meant
`buildChatSystemPrompt`/`buildSubjectContext` both changed from returning a
bare string to a small `{ text, candidateIds? }` wrapper — a mechanical
change for the other three `subjectType`s (`'todo'`/`'plan'`/`'general'`),
which just have `candidateIds` implicitly `undefined`.

## What's NOT built here yet

- **The Home Bridge Bot itself** and the Android side of Home mode (the
  Settings toggle stays disabled) — see `../../README.md`'s "Telegram
  bridge" section for what this round intentionally stops short of.
- **Real conversation summarization.** `../../../docs/architecture.md` §07
  says a long conversation should be "summarized once it gets long enough
  to threaten the context window." What's here instead is a blunt cap
  (`MAX_HISTORY_MESSAGES` / `MAX_STORED_MESSAGES` in `index.ts`) that just
  drops the oldest messages once a conversation gets long. Fine for a
  single-user app's typical conversation length; a real summarization pass
  is a reasonable Phase 4 "polish" item if long conversations turn out to
  matter in practice.
- **A deep join for `subjectType: "plan"` chat context.** The context text
  built for a plan-grounded conversation lists each agenda item's `todoId`
  and rationale, not the todo's actual title (that would mean N extra
  DynamoDB gets per chat turn for very little benefit — the model already
  saw titles when it generated the plan, and the rationale text usually
  carries enough of the "what" on its own).

## About verification

Same no-npm-registry constraint as the rest of `aws-backend` (see
`../../README.md`'s "About verification") — this was never compiled or
run. Unlike some earlier pieces of this backend, though, the actual
Bedrock Converse API shapes used here (`ConverseCommand`'s `toolConfig`/
`toolChoice`, the `Message`/`ToolUseBlock` field names, the IAM
`SpecificToolChoice`/cross-region-inference-profile permission pattern in
`../../infra/lib/ai-stack.ts`) were each checked against AWS's own API
reference and TypeScript type definitions before being written, given this
project's history of real bugs from guessed AWS SDK shapes elsewhere (see
`../../../android-app/README.md`'s "About verification" for the two that
actually happened there).

One specific thing to watch for, not just a hypothetical: a real (if
apparently uncommon) GitHub issue against `aws-sdk-go-v2` reports Bedrock's
Converse API occasionally returning an empty `{}` tool input instead of the
requested structured data when `toolChoice` forces a specific tool with an
Anthropic model. `bedrock.ts`'s `callBedrockTool` treats an empty/missing
tool input as a thrown error rather than silently returning `{}`, so this
should surface as a `status: "failed"` request with a clear error message
rather than a corrupted Plan/Conversation — but if you see requests failing
with "returned an empty input", that known issue is the first thing to
suspect, not a bug in this file's request-building.

The Home-mode dispatch path added this round (`dispatchHomeRequest`,
`buildPlanPrompt`/`buildChatSystemPrompt` being shared with Cloud mode, and
the extraction into `../../shared/ai-finalize.ts`) is unverified the same
no-npm-registry way, plus genuinely untestable end to end until the Home
Bridge Bot exists — see `../../README.md`'s "About verification" item 5 for
what was and wasn't checked.

**Planning mode** (`subjectType: "planning"`, `suggestedDueDateChanges`,
the `buildChatSystemPrompt`/`buildSubjectContext` return-type change to
`{ text, candidateIds? }`) is unverified the same no-npm-registry way — no
real `tsc`/build was run. It reuses `validateChatReply`'s existing
zod-parse-then-throw shape and `finalizePlanResult`'s existing
candidate-id-gating pattern rather than introducing new validation
machinery, and `dispatchHomeRequest`'s destructuring was checked by hand
against the new `ChatSystemPrompt` return type so the Home-mode dispatch
path (which intentionally ignores the new `candidateIds` — see the
"Planning mode" section above) still compiles against it.

Bedrock model access must be requested for the model in
`ai-stack.ts` (Claude Sonnet 4.5 via its `us.` cross-region inference
profile, by default) in your target region before this will actually work
— see `../../README.md`'s "Deploying" section and
`../../../docs/architecture.md` §10.

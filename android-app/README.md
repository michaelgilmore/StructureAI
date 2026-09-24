# android-app

Kotlin / Jetpack Compose client — Phase 1. See
[`../docs/architecture.md` §08](../docs/architecture.md#08--android-app)
for the original intent; a couple of things below are deliberate
deviations from that doc (mainly: manual DI instead of Hilt), noted where
they come up.

**Status: Phase 1 and Phase 2 (AI planning & dialog) are both built and
confirmed working end to end** against the real deployed API and Bedrock,
including the Settings screen and push notifications (a real device test —
create a reminder, get the actual notification) — see "About verification"
below for the real bugs that surfaced (and were fixed) getting there.

## What's here

Auth (Cognito SRP), a todo list and create/edit screen with priority, due
date, recurrence, and reminder — all wired to the real deployed API — plus
an offline-first local cache with a background sync queue, and push
notifications for due-date reminders (client and server both — see below).

**Phase 2 (AI planning & dialog) frontend — confirmed working** — see "AI
planning & dialog (Phase 2)" below, including two real bugs that surfaced
(and were fixed) getting it there.

**Settings screen — confirmed built and working**, including AI-mode
persistence groundwork for Phase 3. See "Settings" below.

**Show/hide completed todos + PDF completion report — built, not yet
verified against a real build.** See "Show/hide completed + PDF report"
below.

**Plan Of the Day ("Today" tab) — built, not yet verified against a real
build.** A new bottom-nav tab listing every open todo due today or
overdue. See "Plan Of the Day (Today tab)" below.

```
android-app/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   pins Gradle 8.9 — see "Building" below
└── app/
    ├── build.gradle.kts        dependencies, BuildConfig fields (API URL, Cognito ids)
    ├── google-services.json    YOU must add this — see "Firebase setup"
    └── src/main/kotlin/cc/gilmore/todoai/
        ├── AppContainer.kt      manual DI — the whole object graph, one file, see below
        ├── TodoAiApplication.kt, MainActivity.kt
        ├── model/                Priority, TodoStatus, PlanScope, ChatSubjectType — mirror aws-backend/shared/types.ts
        ├── data/
        │   ├── local/             Room (v2 — see completedAt below): TodoEntity (cache + outbox in one table), TodoDao, AppDatabase (MIGRATION_1_2)
        │   └── remote/            Retrofit: TodoApiService, AiApiService, AuthInterceptor, NetworkModule, dto/
        ├── auth/                 CognitoAuthManager (SRP), CredentialStore ("remember me" — see below), wrapped by repository/AuthRepository.kt
        ├── repository/            TodoRepository (source of truth), AiRepository (Phase 2), AuthRepository, Mappers
        ├── sync/                  SyncScheduler + SyncWorker (WorkManager)
        ├── connectivity/          ConnectivityObserver
        ├── push/                  TodoFcmService, NotificationHelper
        ├── util/                  DateUtils, PdfReportGenerator (completed-todos PDF — see below)
        └── ui/                    theme/, navigation/, login/, todolist/, tododetail/, planner/, dialog/, settings/, report/, pod/ (Plan Of the Day — see below), components/ (incl. shared TodoRow)
```

## Why manual DI instead of Hilt

`docs/architecture.md` originally named Hilt. I used a hand-written
`AppContainer` instead — see its doc comment for the reasoning, but
briefly: Hilt's annotation processor is one more thing that can fail in a
way I can't diagnose without a real build (see "About verification"), and
this app is small enough that one file readable top-to-bottom is a
reasonable trade for that risk. Room still needs KSP (harder to avoid),
but Hilt's own codegen is the piece skipped. If you'd rather have Hilt,
that's a real option later — `AppContainer` is small and isolated enough
that swapping it out wouldn't touch much else.

## Login: password visibility toggle and "remember me"

The login screen (`ui/login/LoginScreen.kt`) has an eye icon that toggles
the password field between masked and plaintext while typing — that part's
purely cosmetic, no data implications.

"Remember me" is the one with a real tradeoff: checking it stores your
username and password on-device via `auth/CredentialStore.kt`, using
`EncryptedSharedPreferences` (AES256-SIV/AES256-GCM, key held in the
Android Keystore, never in app code or backed up — see
`res/xml/backup_rules.xml` and `data_extraction_rules.xml` for the explicit
exclusion this needed). That's meaningfully better than plaintext storage,
but it is still storing a real password at rest, not a token: anything
that can run as this app on an unlocked device can read it back, the same
as anything else this app does when signed in. `CredentialStore`'s own doc
comment goes into this in more depth, including why "remember me" isn't
actually load-bearing for staying signed in day to day — the Cognito SDK's
own refresh-token persistence already does that for free, with no password
stored anywhere. This feature is purely the convenience of not having to
retype the password on a *new* sign-in (e.g. after an explicit sign-out or
a fresh install).

## Offline sync model

Each todo is one row in Room (`TodoEntity`) with a `syncState` column
(`SYNCED`, `PENDING_CREATE`, `PENDING_UPDATE`, `PENDING_DELETE`) instead of
a separate generic operation-log table. That's a deliberate simplification
over a more "correct" append-only outbox:

- A todo can only be in one pending state at a time, which is true in
  practice for a single-device v1 — you can't out-of-order a create and a
  delete of the *same* todo when there's only one queue slot for it.
- Edit a todo that's still `PENDING_CREATE` (created while offline, never
  synced) → it stays `PENDING_CREATE` with the new field values. No PATCH
  is ever needed; the eventual POST just carries the latest data.
- Delete a todo that's still `PENDING_CREATE` → the local row is deleted
  immediately, no network call ever happens for it.
- Otherwise, delete marks `PENDING_DELETE` (hidden from the UI, not yet
  removed from Room) until the server confirms.

`TodoRepository.syncNow()` — called by `SyncWorker` on a 30-minute
WorkManager schedule (network-constrained, so WorkManager itself defers
and retries as connectivity changes) and right after every local mutation
via `SyncScheduler.syncSoon()` — processes deletes, then creates, then
updates, then does a full `GET /todos` pull to reconcile (server wins for
any row that isn't itself still pending). `TodoListViewModel` also listens
to `ConnectivityObserver` directly so a sync fires the moment the app
notices it's back online, rather than waiting on WorkManager's own
scheduling latency.

What this does *not* handle: conflicting edits to the *same* todo from two
different devices/sessions both offline at once. Fine for "you, one phone"
today; worth revisiting before this app has more than one real user.

## AI planning & dialog (Phase 2)

Two new screens, reachable via a bottom navigation bar added alongside
them (Todos / Plan — Todo detail and the Dialog screen are pushed on top
of whichever tab is active rather than being tabs themselves):

- **Planner** (`ui/planner/`) — pick a scope (day/week/month/year) and a
  start date, optionally add a free-text focus, and generate a plan.
  Shows the model's summary plus an ordered agenda; each item resolves its
  title from the local Room todo cache for display (falling back to a
  placeholder if that todo was deleted locally since the plan was
  generated) and taps through to `TodoDetailScreen`. A "Discuss this plan"
  button opens Dialog grounded in that plan.
- **Dialog** (`ui/dialog/`) — a chat screen grounded in one of three
  subjects (`ChatSubjectType`): a single todo (opened via "Ask AI about
  this" on `TodoDetailScreen`), a generated plan (opened via Planner's
  "Discuss this plan"), or general/no specific subject (opened via a chat
  icon on the todo list's top bar). Multi-turn: the server's
  `conversationId` from the first reply is threaded through subsequent
  sends so the model sees prior turns; losing it (process death, or
  leaving and reopening the screen) just starts a fresh conversation
  rather than crashing.

Both are wired through a new `AiApiService`/`AiRepository` pair
(`data/remote/AiApiService.kt`, `repository/AiRepository.kt`) rather than
extending `TodoApiService`/`TodoRepository` — deliberately, for two
reasons documented on `AiRepository` itself: there's no Room cache behind
either screen (a plan or a chat reply is a live Bedrock call each time,
not offline-first data — see `docs/architecture.md` §07), and a failed
request is surfaced to the screen as an inline error instead of being
swallowed the way `TodoRepository.refresh()` swallows a network failure —
"the AI didn't answer" is something worth showing, not retrying silently.
`mode` is hardcoded to `"cloud"` throughout; there's no Home-mode toggle
in the app yet (Phase 3, once Home mode actually exists — see
`docs/architecture.md` §11).

**Dialog can create todos.** If you clearly ask it to add/create/remember a
task, the assistant creates it for real (server-side, `source: "ai"` — see
`docs/api/README.md`'s "Chat can create todos") as part of the same reply,
rather than needing a separate confirm step. `DialogViewModel.send()`
upserts any `createdTodos` from the response straight into the Room cache
(`TodoRepository.upsertFromServer`) so they're already synced by the time
the reply renders, and `DialogScreen` shows one tappable chip per created
todo under the assistant's bubble — tapping one opens `TodoDetailScreen`
the same way tapping a row anywhere else in the app does. Cloud mode only:
a Home-mode reply still creates the todo(s), but the app finds out via its
next background sync rather than a chip in the chat, since
`telegram-webhook` has no synchronous response channel back to the app —
see that lambda's doc comment.

One nav-graph detail worth flagging: Dialog's route carries a
`subjectId` (a todo id, or a plan's `"{scope}#{periodStart}"` composite
key) and a `subjectLabel` (a todo's title, or a plan's date range — both
arbitrary user/model text). Navigation Compose route strings are just
`&`/`=`-delimited query strings under the hood, so both are
percent-encoded before being placed in the route and decoded on the way
out (`NavGraph.kt`'s `navEncode`/`navDecode`) — a todo titled e.g.
`"Q&A prep"` would otherwise corrupt the route or crash argument parsing
unescaped.

**Confirmed working end to end** against the real deployed API and
Bedrock. Two real bugs surfaced getting there, both fixed:

- Every Planner/Dialog request failed with the app showing "validation
  required." Root cause: `PlanRequestBody`/`ChatRequestBody`'s `kind`
  field (`AiDto.kt`) has a Kotlin default (`= "plan"` / `= "chat"`), and
  kotlinx.serialization's own default is `encodeDefaults = false` — any
  field whose value equals its declared default is silently **omitted**
  from the outgoing JSON, not sent as the default value. Since `kind` was
  always constructed at its default, it was never actually sent, and the
  server's `z.discriminatedUnion('kind', ...)` (`shared/ai-schema.ts`,
  no default for the discriminator) failed validation before it could
  even tell which request kind it was looking at. Fixed by setting
  `encodeDefaults = true` on `NetworkModule.json` — safe against every
  other DTO in the app, since `explicitNulls = false` independently keeps
  omitting genuinely-null fields (confirmed against kotlinx.serialization's
  docs) regardless of `encodeDefaults`.
- After that fix, every request instead failed with
  `AccessDeniedException: Model access is denied due to IAM user or
  service role is not authorized to perform the required AWS Marketplace
  actions`. This one's server-side — see `aws-backend/README.md`'s "AI
  planning & dialog (Phase 2)" for the fix (an additional IAM grant on the
  orchestrator Lambda's role, separate from `bedrock:InvokeModel`/
  `Converse`).

## What doesn't work end-to-end yet

- **Push notifications — confirmed working end to end**, including a real
  device test (created a reminder in the app UI, the reminder-scan Lambda
  picked it up, and the push notification actually arrived on the phone).
  `POST /device/token` (`TodoApiService.registerDeviceToken`) is
  implemented in `aws-backend` — see `aws-backend/lambdas/device-token/`
  and `aws-backend/README.md`'s "Push notifications" section — and needs
  the one manual Firebase service-account secret in Secrets Manager
  (documented there; it's a private key, so it deliberately isn't
  something CDK creates for you) before that stack deploys. Once deployed,
  registration → `reminder-scan`'s SNS publish → FCM is a confirmed
  real end-to-end path. See `aws-backend/TESTING.md` §6 for how to test
  it yourself.
- **Update:** found and fixed a real registration-timing gap while
  reviewing this end to end. `registerDeviceTokenIfNeeded` previously had
  exactly one caller: `TodoFcmService.onNewToken`. That callback normally
  fires once, at first app install, when FCM generates its token — and
  Firebase Messaging auto-initializes independently of Cognito auth, so
  that call typically lands *before* sign-in completes. `POST /device/token`
  requires a signed-in Cognito JWT (`infra/lib/api-stack.ts`'s authorizer
  on that route), so the one real registration attempt would 401 — and
  since the token doesn't change again afterward, `onNewToken` would never
  fire a second time to retry it. A real install could go through
  onboarding and sign-in and simply never end up registered, silently
  (the failure is logged, not surfaced). Fixed by adding
  `TodoRepository.refreshDeviceTokenRegistration()` — fetches the current
  token via `FirebaseMessaging.getInstance().token` (bridged to a suspend
  call with `suspendCancellableCoroutine` rather than pulling in
  `kotlinx-coroutines-play-services` for one call site — verified against
  Firebase's `Task<TResult>` callback contract first) and registers it —
  called once per sign-in from a `LaunchedEffect(isSignedIn)` in
  `NavGraph.kt`, covering both a fresh login and a restored session. Cheap
  to repeat: the server already treats "same token, same user" as a no-op
  touch. `TodoFcmService.onNewToken` is still useful (a real token
  rotation, rare as that is) and unchanged.
- **Update: confirmed on a real device.** Deployed with the Firebase
  secret in place, signed in on the phone, created a todo with a
  near-past reminder from the app UI, and got the actual system
  notification. The registration-timing fix above was real and
  necessary — this section's earlier "unverified against a real build"
  caveat is resolved for push notifications specifically.

## Settings

`ui/settings/` — reachable via the gear icon on the todo list's top bar
(alongside the existing sign-out icon, which stays there too — Settings
also has its own "Sign out" button, but the top-bar one is the faster path
for the one action most people use daily).

- **Account** — the signed-in Cognito username (`AuthRepository.currentUsername`,
  a thin pass-through to `CognitoAuthManager.currentUsername`, which the
  AWS Mobile SDK already tracks — no new plumbing needed there) and a
  "Sign out" button.
- **AI mode** — Cloud shown active, Home shown but disabled. Not a live
  toggle: there's only one mode that actually works until Phase 3 exists
  to answer for Home mode, so both chips are non-interactive rather than
  wired to a setting nothing would do anything with yet.
- **Home (Phase 3)** — "Home Ollama status" and "Telegram link" rows,
  both static "Not available yet." Placeholders, not live status — they
  start meaning something once `home-bridge-bot` and the Telegram webhook
  exist.
- **About** — app version, from `BuildConfig.VERSION_NAME`.

**AI mode persistence (groundwork for Phase 3):** `data/local/AiModeStore.kt`
(Preferences DataStore) and `model/AiMode.kt` now exist, and `AiRepository`
reads the persisted mode on every plan/chat request instead of hardcoding
`"cloud"` in the DTO. This is deliberately *not* a live feature yet —
`SettingsScreen`'s Home chip stays `enabled = false` and nothing reachable
from the UI can write `AiMode.HOME`, so real behavior is unchanged: every
request still ends up built with `AiMode.CLOUD`, exactly as before this
existed. `SettingsViewModel.setMode` has no caller today. It's built now,
ahead of need, so that enabling the toggle once Phase 3 lands is a
`SettingsScreen`-only change (flip `enabled`, point each chip's `onClick`
at `setMode`) instead of new storage/repository plumbing built under time
pressure later.

## Show/hide completed + PDF report

Two related additions to the todo list, both reachable from new icons on
`TodoListScreen`'s top bar (alongside the existing Chat/Refresh/Sign
out/Settings ones):

- **Show/hide completed** — an eye icon toggles `TodoListViewModel`'s
  in-memory `showCompleted` flag, which filters the list (`status == DONE`
  hidden by default). Deliberately not persisted across app restarts (no
  DataStore, unlike `AiModeStore`) — this is a lightweight view filter, not
  a preference worth the extra moving part.
- **Completed todos report** (`ui/report/ReportScreen.kt` +
  `ReportViewModel.kt`) — pick a "From"/"To" date range (reusing
  `ui/components/Pickers.kt`'s `DueDatePicker`, same as everywhere else
  that needs one) and generate a PDF listing every todo completed in that
  range, then share/view it via the system share sheet.

Both depend on a new `completedAt` field, added end-to-end:

- **Backend** (`aws-backend/shared/types.ts`, `lambdas/todo-api/index.ts`):
  server-derived only, never client-settable. `updateTodo` stamps it to
  the same timestamp as `updatedAt` exactly when a PATCH transitions
  `status` to `'done'`, clears it exactly when a PATCH transitions away
  from `'done'` (reopening), and leaves it alone (carried forward) for any
  other edit — reusing the transition-detection (`completingNow`) that
  already existed there for recurrence materialization. Deliberately not
  the same as `updatedAt`, which moves on every edit regardless of
  completion — a date-range report needs the date something was actually
  completed.
- **Android**: `TodoEntity`/`TodoDto` both gained a `completedAt: String?`
  field (see their doc comments). `AppDatabase` bumped `version = 1 → 2`
  with an additive `MIGRATION_1_2` (`ALTER TABLE todos ADD COLUMN
  completedAt TEXT`) — deliberately **not**
  `fallbackToDestructiveMigration()`, since this table doubles as the
  offline outbox (see "Offline sync model" above); a destructive migration
  could silently drop real unsynced edits still waiting on `SyncWorker`.
  `TodoRepository.updateTodo` also stamps/clears `completedAt`
  optimistically on the same status transition, client-side, so a todo
  completed while offline shows its "Completed <date>" row and appears in
  the report immediately — not only after the next sync pulls the
  server's own value back down.

The report itself is generated client-side, not via a new backend
endpoint: `TodoRepository`'s Room cache already holds a full local copy of
every non-deleted todo (single-user app, already synced), so
`ReportViewModel` just does a one-shot query (`TodoDao.getAllOnce()` →
`TodoRepository.getAllTodosOnce()`) and filters by `completedAt` in
memory, rather than adding server-side date-range query support for a
single-device app that doesn't need it.

`util/PdfReportGenerator.kt` renders the PDF with Android's built-in
`android.graphics.pdf.PdfDocument` — manual `Canvas`/`Paint` drawing and
manual pagination, no third-party PDF library, matching this project's
minimal-dependency stance elsewhere (see "Why manual DI instead of Hilt"
above). The file is written to `context.cacheDir/reports/` and exposed to
the share sheet via a new `FileProvider` (`AndroidManifest.xml`'s new
`<provider>` entry + `res/xml/file_paths.xml`) — no legacy storage
permission needed, scoped-storage-compliant.

## Plan Of the Day (Today tab)

A third bottom-nav tab, `ui/pod/PodScreen.kt` + `PodViewModel.kt`, placed
first (before Todos/Plan). Lists every **open** todo whose `dueDate` is
today or earlier, split into an "Overdue" section and a "Due today"
section — a todo with no due date never appears here, same as it doesn't
on any other date-based view in this app. This is a plain mechanical
filter, no AI/Bedrock call involved — not to be confused with `PlannerScreen`
(Phase 2's AI-generated day/week/month/year agenda), which is a different
screen reached from the existing "Plan" tab.

No new data layer needed: `PodViewModel` filters the same live stream
`TodoListViewModel` already reads (`TodoRepository.observeTodos()`), so a
completion or edit made anywhere in the app updates this screen
immediately, same as everywhere else. Each row is the same
`ui/components/TodoRow.kt` used by the main todo list — pulled out of
`TodoListScreen.kt` into its own file as part of this change specifically
so both screens render an identical row instead of two copies drifting
apart — with the same checkbox-to-complete/tap-to-open/delete behavior.

One known rough edge, documented in `PodViewModel`'s doc comment: "today"
is computed fresh each time the underlying todo list changes, not on a
timer, so this screen won't re-sort itself out of "Overdue" into some
past-midnight state purely from the clock ticking over with nothing else
happening — a manual refresh (the same refresh icon `TodoListScreen` has)
or any edit re-triggers it. Not expected to matter in practice for a
single-user app you open more than once a day, but worth knowing about.

The app's start destination was originally left unchanged (`TODO_LIST`) when
this tab was first added, specifically to avoid touching the notification-tap
routing logic that lived on `TODO_LIST`'s `composable` block in
`NavGraph.kt` — **since superseded**: the start destination is now `POD`
("Today"), and that routing logic moved with it. See "Splash screen +
default tab" below for the full story.

## Splash screen + default tab

`ui/splash/SplashScreen.kt`: a full-screen brand moment shown on every cold
start, before Login or any app screen — "Structure" plus the tagline "Add
Structure to your life" over a 5-second progress bar, on the same brand
green as the launcher icon (`@color/launcher_background`, `#1B5E20`). The
three-block "building" motif from `res/drawable/ic_launcher_foreground.xml`
reappears here too, scaled up: each block rises into place and the
checkmark lands on the tallest one as the bar fills, all driven off the
same `Animatable<Float>` as the progress bar itself — not two independently
timed animations that could drift apart.

`NavGraph.kt`'s `TodoAiNavGraph` gates everything else behind a `showSplash`
boolean (`SplashScreen(onFinished = { showSplash = false })`, then an early
`return`) rather than making the splash a nav-graph route — it's shown
exactly once per process lifetime and is never something you'd navigate
back to, so it didn't need to be part of the back stack at all. The
session-check and device-token-registration `LaunchedEffect`s are
deliberately declared *before* that gate, not after, so they start running
immediately in parallel with the 5-second animation instead of waiting for
it — by the time the splash finishes, `isSignedIn` has almost always
already resolved.

**The default screen changed as part of this**: `NavHost`'s
`startDestination` is now `Routes.POD` ("Today") instead of `Routes.TODO_LIST`.
The one piece of logic that assumed `TODO_LIST` was always first — the
`LaunchedEffect` that turns a reminder notification tap into an immediate
navigation to that todo's detail screen — had to move with it: it's no
longer nested inside `composable(Routes.TODO_LIST) { ... }` (which now only
composes if you actually switch to the Todos tab) but hoisted up to
`TodoAiNavGraph`'s top level, alongside `backStackEntry`/`currentRoute`, so
it fires regardless of which tab the user lands on first.

Two Kotlin-source risks worth naming, checked more carefully than
typical-usage guessing for that reason: `Animatable(0f)`'s no-type-converter
constructor is the dedicated `Float` overload Compose ships specifically for
cases like this (confirmed against `androidx.compose.animation.core`'s
actual API, not assumed from the generic `Animatable<T, V>` constructor
shape), and `LinearProgressIndicator(progress = <Float>, ...)` deliberately
uses the older Float-parameter overload rather than the newer
`progress: () -> Float` lambda one some recent Material3 versions prefer —
both exist side by side (the Float one is deprecated-but-present, not
removed), and the older one is the safer bet across `compose-bom`
`2024.10.01`'s exact Material3 point version without a real Gradle sync to
confirm which lambda overloads landed. If this does throw an
overload-resolution error, that line is the first place to look.

## Add-todo FAB on Today, and the keyboard-covers-fields fix

Two small, related changes to the add/edit todo flow:

**Today screen gets its own add button.** `PodScreen.kt` now has a
`floatingActionButton` (same `Icons.Filled.Add` FAB `TodoListScreen.kt`
already had), wired in `NavGraph.kt` to navigate to `Routes.TODO_DETAIL`
with a new `defaultDueDate` query arg set to today's date
(`DateUtils.formatDueDate(LocalDate.now())`). The Todos tab's existing FAB
is untouched — it still navigates to `Routes.TODO_DETAIL` with no args, so
a todo created from there still starts with an empty due date, same as
before.

The new arg threads through as `TodoDetailScreen(..., defaultDueDate:
LocalDate? = null)` → `TodoDetailViewModel(repository, todoId,
defaultDueDate)`, which only applies it when seeding the *initial*
`TodoDetailUiState` for a brand-new todo (`todoId == null`); editing an
existing todo builds its `TodoDetailUiState` entirely from the loaded todo
in `init`, so `defaultDueDate` is never in a position to overwrite a real
todo's due date. `Routes.TODO_DETAIL`'s route string now declares two
independent optional query args (`todoId`, `defaultDueDate`), the same
either-or-both-or-neither pattern the `DIALOG` route already used — every
existing caller that only passes `todoId` (the Todos tab's `onOpenTodo`,
the notification-tap deep link, `DialogScreen`'s `onOpenTodo`) is
unaffected, since the new arg just resolves to its `null` default.

**Keyboard no longer hides the lower fields.** `TodoDetailScreen.kt`'s form
`Column` already had `.verticalScroll(rememberScrollState())`, but nothing
told it to make room for the on-screen keyboard, so Due date/Reminder/
Recurrence stayed pinned behind it with no way to scroll them into view.
Fixed two ways together:

- `Modifier.imePadding()` added as the last (innermost) modifier on that
  `Column`, after `.verticalScroll(...)` — that ordering matters: applying
  it *inside* the scroll makes the scrollable *content* taller by the
  keyboard's height rather than just padding the viewport around it, which
  is what actually gives the scroll something to scroll so the last field
  can reach above the keyboard.
- `android:windowSoftInputMode="adjustResize"` added to `MainActivity` in
  `AndroidManifest.xml`. This app targets SDK 35 (Android 15), which
  enforces edge-to-edge and stops the window from resizing itself on
  keyboard show regardless of this flag — `imePadding()` is what actually
  does the work there — but `adjustResize` is still the documented pairing
  for `imePadding()` and is what makes this behave the same way on
  pre-Android-15 devices, where the window resize model still applies.

## Firebase setup (required before this builds at all)

The `com.google.gms.google-services` Gradle plugin fails the build
outright if `app/google-services.json` is missing — this isn't optional
polish, it blocks `gradle sync` entirely:

1. Create a Firebase project (or reuse one) at https://console.firebase.google.com.
2. Add an Android app to it with package name **`cc.gilmore.todoai`** —
   must match `applicationId` in `app/build.gradle.kts` exactly.
3. Download the generated `google-services.json` and put it at
   `android-app/app/google-services.json`.

## Fill in the real backend values

`app/build.gradle.kts` exposes four `BuildConfig` fields (`API_BASE_URL`,
`COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, `COGNITO_REGION`, read in both
the `debug` and `release` blocks — Gradle doesn't inherit `debug`'s into
`release`), sourced from `android-app/local.properties` rather than
hardcoded in the file. These aren't secrets — this app's Cognito app
client has no client secret (see `auth/CognitoAuthManager.kt`), so knowing
the Pool ID/Client ID/API URL alone gets you nothing without a real
password — but there's no reason to publish one specific deployment's
exact infra in a tracked file either, and `local.properties` is the
standard place Android tooling already keeps machine-local config
(`sdk.dir` lives there too), so these are one more line in a file that was
already gitignored.

**Setup:** copy `android-app/local.properties.example` to
`android-app/local.properties` (the `.example` file is safe to commit —
just placeholders; the real one is gitignored) and fill in:

- `api.baseUrl` — the `ApiUrl` output from `aws-backend`'s `TodoAi-Api`
  stack, e.g. `https://xxxxxxxxxx.execute-api.us-east-1.amazonaws.com/`
  (keep the trailing slash — Retrofit's relative paths depend on it).
- `cognito.userPoolId`, `cognito.clientId` — the `UserPoolId` /
  `UserPoolClientId` outputs from `TodoAi-Auth`.
- `cognito.region` — e.g. `us-east-1`.

Get the current values with the same commands `aws-backend/TESTING.md`
uses:
```powershell
aws cloudformation describe-stacks --stack-name TodoAi-Auth --query "Stacks[0].Outputs" --output table
aws cloudformation describe-stacks --stack-name TodoAi-Api --query "Stacks[0].Outputs" --output table
```

Leaving `local.properties` missing, or a key out of it, doesn't break the
build — `build.gradle.kts` falls back to the same obvious
`REPLACE_WITH_YOUR_...` placeholder strings this section used to have
hardcoded, so a fresh clone still compiles; it'll just be pointed at
nothing until you fill this in.

## Building

Prerequisite: Android Studio (Ladybug or newer) is the path of least
resistance — it bundles a compatible JDK. Open `android-app/` as a project
and let it sync; that sync is also your first real build check, since none
of this has been compiled anywhere yet.

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are
included (generated with a real `gradle wrapper --gradle-version 8.9` run,
not hand-typed), so `.\gradlew.bat assembleRelease` also works standalone
from a terminal, no Android Studio required for the build itself (you'd
still want the IDE for editing/debugging).

**If a build ever complains about a Gradle 7.5 distribution** (a
"Could not locate the Gradle launcher JAR" error, or anything else
mentioning `gradle-7.5`) despite `gradle/wrapper/gradle-wrapper.properties`
correctly pinning 8.9: that means something regenerated the wrapper using
a different default instead of reading this project's properties file —
older Android Studio versions have been known to do this for a project
that's missing the wrapper jar. Now that the real `gradlew`/`gradlew.bat`/
`gradle-wrapper.jar` are checked in, this shouldn't happen again, but if it
does: delete the stale cached distribution
(`Remove-Item -Recurse -Force "$env:USERPROFILE\.gradle\wrapper\dists\gradle-7.5-all"`)
and re-sync — Android Studio should then use the wrapper files already in
the project instead of generating its own.

## Debugging a crash on launch

Logging is deliberately heavy along the whole app-launch path — every
dependency `AppContainer` builds, `TodoAiApplication.onCreate`,
`MainActivity.onCreate`, plus the sync engine (`TodoRepository`,
`SyncWorker`) and Cognito auth calls — because a failure early in that path
(e.g. `AppContainer`'s eager construction of `CognitoAuthManager`) takes
the whole process down before a single screen draws, with nothing visible
in the UI to go on.

Everything uses a `TodoAi.*` tag prefix so you can filter to just this
app's own logs and skip the rest of the system noise:

```powershell
adb logcat -v time "TodoAi.*:V" "*:S"
```

or use Android Studio's Logcat panel with a tag filter of `TodoAi.`.

`TodoAi.Application` also installs a default uncaught-exception handler
before anything else runs, so **any** crash — not just one during
`AppContainer` construction — gets one clear `"FATAL: uncaught exception on
thread '...'"` line with the real stack trace before the process dies, even
if it happens well after launch.

The most likely first-run cause: `app/build.gradle.kts`'s
`COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, and `API_BASE_URL`
`buildConfigField`s still holding their `REPLACE_WITH_YOUR_*` placeholder
values (see "Fill in the real backend values" above) rather than your actual deployed
`aws-backend` outputs. `TodoAi.CognitoAuth` and `TodoAi.NetworkModule` both
log the exact values they're constructed with, so that's the first thing
to check in Logcat before looking at anything else.

## About verification

Same situation as `aws-backend`, one level worse: I don't have an Android
SDK in this session at all, and Maven Central / Google's Maven repo are
both network-blocked here exactly like `registry.npmjs.org` was — I
confirmed with a direct request, same 403. That means this was never
Gradle-synced, never compiled, never run — not even a dependency
resolution check. Everything here is hand-written and hand-reviewed
against how I understand these APIs to work, not verified against the
actual libraries.

The single riskiest piece: `auth/CognitoAuthManager.kt` wraps
`com.amazonaws:aws-android-sdk-cognitoidentityprovider` (the SRP auth
client) — the constructor and callback-interface shapes there are written
from typical/documented usage of that SDK, not confirmed against the
actual jar. If this fails to compile, that file is the first place to
look; the fix is likely a small signature adjustment, not a design
problem.

Before you spend real time on this: open it in Android Studio, let it
sync, and send me whatever the first error is — same loop as
`aws-backend`. I'd rather fix a real compiler error than have you debug my
guesses.

One exception to "not confirmed against the actual jar": `auth/CredentialStore.kt`'s
use of `MasterKey.Builder`/`EncryptedSharedPreferences.create` was checked
against AndroidX's actual source (both classes' constructors and static
factory signatures), not just typical-usage guessing, precisely because
`com.amazonaws:aws-android-sdk-cognitoidentityprovider` above already
burned two real bugs this way (a wrong import path and a wrongly-declared
non-null parameter that crashed at runtime) — worth the extra step for
anything touching stored credentials specifically.

**Update (Phase 2 frontend pass): now confirmed working end to end**,
against the real deployed API and Bedrock — everything under "AI planning
& dialog" above (`AiApiService`/`AiRepository`, `PlannerScreen`/
`DialogScreen` + their ViewModels, the `NavGraph.kt` bottom-nav rewrite,
the `TodoDetailScreen.kt`/`TodoListScreen.kt` edits) has moved from
"unverified" to "verified by an actual run," after two real bugs (see "AI
planning & dialog" above) were found and fixed. Two things worth naming
specifically from when this was still unverified, since they were
deliberately checked more carefully than typical-usage guessing and it's
worth recording why that paid off (or didn't):

- The icons used for the new bottom-nav items and buttons
  (`Icons.Filled.Checklist`, `CalendarMonth`, `Chat`, `Send`) were checked
  against `app/build.gradle.kts`'s dependencies — the app already pulls in
  `androidx.compose.material:material-icons-extended` (the full Material
  icon set, not just the small default subset bundled with
  `material3`), and these are common, long-standing icon names in that
  set — but the exact set of icon names available is still something only
  a real Gradle sync confirms.
- `AiRepository`'s JSON handling (`Json.decodeFromJsonElement`,
  `AiRequestResponseDto.result` as a raw `JsonElement` decoded per-`kind`)
  was checked against kotlinx.serialization's actual API docs, not just
  typical-usage memory — the exact same discipline
  `auth/CredentialStore.kt` got above, for the same reason: this is the
  one new piece of this pass doing something a little less standard than
  "define a data class and let Retrofit's converter handle it."

The plain Column-instead-of-LazyColumn choice in `PlannerScreen.kt`'s
agenda list is deliberate, not an oversight: nesting a `LazyColumn` inside
the screen's outer `verticalScroll` `Column` is a real, well-documented
Compose crash ("vertically scrollable component was measured with an
infinity maximum height constraint"), and plan items are capped
server-side (`MAX_PLAN_CANDIDATES` = 60 in `ai-orchestrator/index.ts`) —
small enough that a non-lazy list is the correct choice here, not just a
workaround.

**Update (Settings screen pass):** back to unverified for this newest
piece — `ui/settings/SettingsScreen.kt`/`SettingsViewModel.kt`, the
`AuthRepository.currentUsername` pass-through, and the `NavGraph.kt`/
`TodoListScreen.kt` wiring for it. Lower risk than most additions so far,
though: no new library surface (everything used —
`AuthRepository`/`CognitoAuthManager`, `FilterChip`, `BuildConfig.VERSION_NAME`
— already existed and compiles elsewhere in this app), and
`Icons.Filled.Settings` specifically (as opposed to some of the Phase 2
icon names, checked only against dependency presence — see above) is one
of the handful of icons bundled in compose-material's small default
subset, not just the extended set, so it's about as safe a bet as an icon
name gets without an actual Gradle sync.

**Update:** the "no new library surface" confidence above didn't cover
argument-level API shape, and that's exactly where the first real bug in
this pass showed up. `SettingsScreen.kt` had two calls of the form
`Modifier.padding(horizontal = 16.dp, top = 8.dp, bottom = 8.dp)` — mixing
named arguments from `Modifier.padding(horizontal: Dp, vertical: Dp)` and
`Modifier.padding(start: Dp, top: Dp, end: Dp, bottom: Dp)`, two distinct
overloads with no combined form. Gradle failed both with "None of the
following candidates is applicable," reported for the `Text` modifier in
the AI-mode section (line 86); the identical mistake in the private
`SectionHeader` composable hadn't been reached by the compiler yet but
would have failed the same way. Fixed by spelling out all four sides
(`start`/`top`/`end`/`bottom`) at both call sites. Re-grepped every
`.padding(...)` call across `android-app/` afterward — no other instance
of the mixed-overload pattern turned up.

**Update:** confirmed — **the Settings screen builds and runs correctly**
with that fix in. This section's "About verification" caveat for the
Settings-screen pass is resolved.

**Update (AI mode persistence):** new, unverified piece —
`data/local/AiModeStore.kt`, `model/AiMode.kt`, and the `AiRepository`/
`SettingsViewModel`/`SettingsScreen`/`AppContainer` wiring for them (see
"Settings" above). Same low-risk shape as the original Settings pass: no
new library beyond `androidx.datastore:datastore-preferences`, which was
already sitting in `app/build.gradle.kts` unused, presumably added ahead
of time for exactly this. The one real risk worth naming: `Flow.catch`'s
`emit(emptyPreferences())` inside `AiModeStore.mode` assumes `catch`'s
receiver can call `emit` directly (it can — `catch` is a `FlowCollector`
extension, unlike a plain `try/catch` around a `Flow` builder) — reviewed
against kotlinx.coroutines' own docs/samples for that operator, not just
assumed, but still unbuilt on my end like everything else in this list.

**Update (show/hide completed + PDF report pass):** new, unverified piece
— everything under "Show/hide completed + PDF report" above. The two
riskiest bits, checked more carefully than typical-usage guessing for that
reason:

- `AppDatabase`'s `MIGRATION_1_2` (`ALTER TABLE todos ADD COLUMN
  completedAt TEXT`) — Room's `Migration`/`SupportSQLiteDatabase` API
  shape was checked against Room 2.6.1's actual class signatures, since a
  wrong migration here is the one mistake in this pass that could
  genuinely corrupt or lose local data (the offline outbox) rather than
  just fail to compile. Still unconfirmed against a real Room schema
  export, though — there is no `app/schemas/` directory in this repo at
  all (`exportSchema = true` has never actually run), so I couldn't diff
  this migration against a captured v1 schema the way you normally would.
  **If you hit a Room crash on first launch after pulling this change,
  this migration is the first place to look**, and the safe fallback if
  something's wrong with it is to uninstall/reinstall (losing any
  still-unsynced local edits) rather than guess at a fix blind.
- `android.graphics.pdf.PdfDocument`'s API (`startPage`/`finishPage`/
  `PageInfo.Builder`/`writeTo`) was checked against Android's actual
  platform docs, not just remembered — it's used correctly as far as I can
  tell, but the manual pagination logic (the `y > PAGE_HEIGHT - MARGIN -
  ENTRY_GAP` check in `PdfReportGenerator.generate`) is exactly the kind
  of off-by-one-prone code a real device test would catch fastest — worth
  generating a report with enough completed todos to force a second page
  as your first real test of this feature.

One pre-existing issue noticed while reading `todo-api/index.ts` for this
pass, **not fixed, out of scope for this change**: `buildItem`'s `const
status: TodoStatus = input.status ?? 'open'` means a PATCH that omits
`status` entirely would silently reset a todo to `'open'`, because
`toInput(existing)` (used to seed the merged input for a partial update)
doesn't carry `status` forward. This is currently masked because
`TodoEntity.toUpdateRequest()` always sends `status` on every PATCH
(full-replace, not a partial diff — see its own doc comment), so no real
request today triggers it. Worth fixing before anything else ever PATCHes
this endpoint with a genuinely partial body.

**Update (Plan Of the Day pass):** new, unverified piece —
`ui/pod/PodScreen.kt`/`PodViewModel.kt`, the extraction of
`ui/components/TodoRow.kt` out of `TodoListScreen.kt`, and the `NavGraph.kt`
third-tab wiring. Low risk: no new library surface at all (everything used
— `LazyColumn`, `NavigationBarItem`, `TodoRepository.observeTodos()`,
`DateUtils.parseDueDate`, `Icons.Filled.Today`) already compiles elsewhere
in this app or is one of the well-established default-set icon names.
`Icons.Filled.Today` specifically wasn't previously used anywhere in this
app, unlike `Checklist`/`CalendarMonth` (already load-bearing on the
existing two tabs) — it's a long-standing, common Material icon name, but
still worth a glance at Android Studio's icon picker on first sync just to
be sure it resolves. The `TodoRow` extraction itself is a pure
copy-and-import-fix with no logic change, so the main risk there is a
stray unused-import warning, not a real bug — I re-checked
`TodoListScreen.kt`'s import list by hand after removing the private copy.

**Update (AI-can-create-todos pass):** new, unverified piece — the
`ChatSendResultDto.createdTodos` field (`AiDto.kt`), `TodoRepository.upsertFromServer`,
the `DialogViewModel`/`DialogScreen` changes (`CreatedTodoUi`, the chip row
under an assistant bubble), and the `NavGraph.kt`/`DialogScreen` wiring for
`todoRepository`/`onOpenTodo`. Backend half (`shared/todo-item.ts`,
`shared/ai-schema.ts`'s `aiCreateTodoSchema`, `shared/ai-finalize.ts`'s
`createRequestedTodos`, the `ai-orchestrator`/`telegram-webhook` call
sites) is covered by `aws-backend/README.md`'s own verification notes —
same no-npm-registry, never-actually-run caveat as the rest of that side.

On the Android side, the one new library surface is `AssistChip`/
`AssistChipDefaults` (`androidx.compose.material3`) — not previously used
anywhere in this app, unlike `FilterChip` (already load-bearing in Settings
and Planner). Checked against Compose Material3's actual API (a stable,
long-standing component — `onClick`/`label`/`leadingIcon` are its
documented constructor params, and `AssistChipDefaults.IconSize` is the
standard way to size a chip's leading icon) rather than guessed from
`FilterChip`'s shape, but still only confirmed by reading, not by an actual
Gradle sync. `Icons.Filled.Checklist` (reused for the chip's leading icon)
was already confirmed available in this app's icon set for the "Todos" bottom-nav
tab, so no new icon-name risk there. Everything else — `TodoRepository.upsertFromServer`
reusing the existing `TodoDto.toEntity`/`dao.upsert` pair, the DTO field
addition (`ChatSendResultDto.createdTodos`, defaulted so it degrades
cleanly against an older-deployed backend) — is a straightforward extension
of patterns already verified working in the original AI planning & dialog
pass.

**Update (rename to "Structure"):** display-name-only rename, scoped
deliberately — the Kotlin package (`cc.gilmore.todoai`), class/log-tag
names, and the AWS resource names all still say "Todo AI"/"todo-ai"; only
what a user actually sees changed. `res/values/strings.xml`'s `app_name`
is now the single source of truth ("Structure"); `LoginScreen.kt`,
`TodoListScreen.kt`, and `SettingsScreen.kt` previously each hardcoded
their own separate `"Todo AI"` literal for their title/heading text — all
three now read `stringResource(R.string.app_name)` instead, so this exact
multi-location drift (three copies of the name, one per screen) can't
happen again on the next rename. No new library surface — `stringResource`
is a standard, long-established Compose API already implicitly available
via the `androidx.compose.ui` dependency this app already has. Low risk,
but still unbuilt on my end like everything else in this list.

**Update (splash screen + default-tab change):** new piece — see
"Splash screen + default tab" above for the full design, including the two
API-shape risks I checked more carefully than typical-usage guessing
(`Animatable(0f)`'s Float overload, and using `LinearProgressIndicator`'s
older Float-parameter form rather than the newer lambda one). Beyond
those two, `androidx.compose.animation`'s `AnimatedVisibility`/`fadeIn`/
`slideInVertically` are long-standing, stable APIs already exercised
elsewhere in Jetpack Compose generally (if not yet in this specific
codebase) — lower risk than the two flagged above. The `NavGraph.kt`
refactor (hoisting the notification-deep-link `LaunchedEffect` out of
`composable(Routes.TODO_LIST)` to the top level, changing
`startDestination` to `Routes.POD`) is a structural change worth a
deliberate first-launch test on a real device: confirm the app opens on
"Today," and separately confirm tapping a reminder notification still
lands on that todo's detail screen (not just on "Today") — both paths
share the same `initialTodoIdFromNotification` value but now take
different code paths to get there than before this change.

**Update (Today FAB + keyboard-scroll fix):** new, unverified piece — see
"Add-todo FAB on Today, and the keyboard-covers-fields fix" above. No new
library surface (`FloatingActionButton`/`Icons.Filled.Add` were already
load-bearing on `TodoListScreen.kt`'s identical FAB; `imePadding()` is a
standard, long-standing `androidx.compose.foundation.layout` modifier).
The one thing genuinely worth a real-device check rather than just a
Gradle sync: whether `imePadding()` alone is sufficient on this app's exact
compose-bom/`androidx.activity` versions, or whether `MainActivity` also
needs an explicit `enableEdgeToEdge()`/`WindowCompat.setDecorFitsSystemWindows(window,
false)` call for IME insets to be dispatched into Compose correctly on
some OS versions — this app currently does neither, and I couldn't find
a way to confirm which configurations need it without a real build. If the
lower fields are still hidden behind the keyboard after pulling this
change, that's the first thing to try next.

**Update (backend config moved to local.properties):** new, unverified
piece — see "Fill in the real backend values" above for the full picture.
`app/build.gradle.kts`'s top-level `localProperties`/`localProperty(...)`
reads use only `java.util.Properties` and `java.io.FileInputStream`, both
plain JDK, not Android/AGP API surface — about as low-risk as a build
script change gets, and `rootProject.file("local.properties")` (resolving
to `android-app/local.properties`, the Gradle root for this module, not
the outer git repo root) is a long-standing, stable Gradle API. The one
thing this genuinely changes your workflow: **if you already hand-edited
the real values into `app/build.gradle.kts` directly** (as opposed to a
`local.properties` file) before this change, that file now reads from
`local.properties` instead — copy `local.properties.example` to
`local.properties` and fill in the same four values, or the build will
fall back to the `REPLACE_WITH_YOUR_...` placeholders and silently point
at nothing. This was prompted by a source-control review that found the
real Pool ID/Client ID/API URL had been hand-edited into the tracked
`build.gradle.kts` — not secrets (see above), but worth keeping out of a
committed file on general principle; the review's one actual finding, a
real Cognito password sitting in plaintext in some now-gitignored scratch
scripts, was unrelated to this change and already fixed separately.

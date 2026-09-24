# aws-backend

Serverless AWS stack, defined as code with the CDK (TypeScript). See
[`../docs/architecture.md` §05](../docs/architecture.md#05--aws-backend)
for the design and the DynamoDB single-table shape.

**Status:** Phase 0 (Todo CRUD API, reminder scan), push notifications (a
real device test), and Phase 2 (Bedrock plan generation + dialog) are
**deployed and confirmed working end to end** against the real AWS account.
Phase 3's AWS backend half (this round — Home-mode dispatch in
`ai-orchestrator`, `telegram-webhook`, `home-status`, `ai-request-timeout`)
is **built, not yet deployed or tested** — it needs the two Telegram bots
set up (see "Telegram bridge (Phase 3)" below) and, to actually complete a
Home-mode request end to end, the Home Bridge Bot itself, which is
deliberately the *next* round of work, not this one. See "About
verification" below for the real bugs that surfaced (and were fixed)
getting the deployed parts working — several were real, hand-review alone
didn't catch them, so read the code, don't just run it.

## Layout

- `bin/todo-ai.ts` — the CDK app entry point; instantiates all six stacks.
- `infra/lib/` — the stacks: `data-stack.ts` (DynamoDB), `auth-stack.ts`
  (Cognito), `api-stack.ts` (HTTP API + the todo-api Lambda),
  `notifications-stack.ts` (SNS + EventBridge reminder scan), `ai-stack.ts`
  (Bedrock), `telegram-stack.ts` (Phase 3 — see below).
- `lambdas/` — one folder per function: `todo-api/`, `reminder-scan/`,
  `device-token/`, `sns-platform-app-provider/` (the Phase 1
  push-notification follow-up — the latter is a custom-resource Lambda,
  see `notifications-stack.ts`'s doc comment for why), `ai-orchestrator/`
  (Phase 2, and now Phase 3's Home-mode dispatch — see below), and
  `telegram-webhook/`, `home-status/`, `ai-request-timeout/` (Phase 3 — see
  below).
- `shared/` — types, zod validation schemas, the recurrence-rule
  calculator, and a shared DynamoDB client — used by both `infra/`
  (bundled into the lambdas via esbuild) and `test/`.
- `test/` — unit tests for `shared/` (jest).

## What Phase 0 actually built

- The DynamoDB table + sparse `GSI1` reminder index.
- Cognito (self-sign-up off — see `infra/lib/auth-stack.ts` for how to
  create the one account by hand after deploying).
- `GET/POST/PATCH/DELETE /todos[/{id}]`, Cognito-authenticated, scoped to
  the caller's `sub`.
- Recurrence: completing a recurring todo materializes its next occurrence
  (see `docs/decisions/0004-recurrence-and-reminders-in-v1.md`).
- `reminder-scan`, on a 5-minute EventBridge schedule, publishing due
  reminders to an SNS topic.

## Push notifications (Phase 1 follow-up)

The gap flagged above — "nothing subscribed to the reminder topic" — is now
closed: `POST /device/token` (see `lambdas/device-token/README.md`) lets
the Android app register its FCM token as an SNS platform endpoint and
subscribe it to the reminder topic, and `reminder-scan` now publishes a
structured per-protocol payload (`MessageStructure: 'json'`, a `GCM` key
shaped as `{data: {todoId, title, body}}`) so it lands as the `data`
payload `TodoFcmService.onMessageReceived` expects — see
`lambdas/reminder-scan/index.ts` and `infra/lib/notifications-stack.ts`.

**One manual step is required before any of this will deploy**, because a
Firebase service-account private key has no business in CDK synth output
or version control:

1. In the [Firebase console](https://console.firebase.google.com/), your
   project → gear icon → Project settings → Service accounts → "Generate
   new private key". This downloads a JSON file.
2. Store its full contents as a Secrets Manager secret named exactly
   `todo-ai/fcm-service-account` (plaintext, not key/value — the whole
   JSON file as one string):
   ```powershell
   aws secretsmanager create-secret `
     --name todo-ai/fcm-service-account `
     --secret-string (Get-Content -Raw path\to\downloaded-key.json)
   ```
3. Deploy as usual (`npm run deploy`). `infra/lib/notifications-stack.ts`
   looks the secret up by name (`Secret.fromSecretNameV2`) — if it's
   missing, that stack fails to deploy with a clear "secret not found"
   error rather than something mysterious.

See `aws-backend/TESTING.md` for how to test `POST /device/token` and
confirm a push actually arrives on the phone.

**Update: confirmed end to end on a real device.** Deployed with the
Firebase secret in place, signed in on the phone, created a todo with a
near-past reminder from the app UI, and the push notification actually
arrived. Getting there also surfaced a real Android-side bug — device
registration was only ever attempted once, at first app install, often
before sign-in completed, so it would silently never succeed for a real
install — see `android-app/README.md`'s "What doesn't work end-to-end
yet" for the fix (`TodoRepository.refreshDeviceTokenRegistration`, called
once per sign-in). Nothing on this backend side needed to change.

**Update: that "confirmed" was about delivery, not content — a second real
bug found.** The push notification was arriving, but with generic
text instead of the todo's title. `lambdas/reminder-scan/index.ts`'s `GCM`
payload was the older flat legacy-GCM shape; this project's platform
application actually authenticates via a Firebase service account (FCM's
v1 API), which SNS requires a different, more deeply-nested payload shape
for (`{fcmV1Message: {message: {data: {...}}}}` — see that lambda's
README, "About this fix," for the full explanation and the AWS doc this
was checked against). Fixed there; **not yet re-verified against a real
device** — redeploy and confirm the next due reminder actually shows the
todo's title before considering this closed.

## AI planning & dialog (Phase 2)

`POST /ai/request` / `GET /ai/request/{reqId}` — plan generation for all
four scopes (day/week/month/year) and a dialog screen's backend,
Bedrock-backed in Cloud mode. See `lambdas/ai-orchestrator/README.md` for
the design and `docs/api/README.md` for the wire contract.

`mode: "home"` now dispatches over Telegram (see "Telegram bridge (Phase
3)" below) instead of being rejected — but the Android app's toggle for it
stays disabled for now, since a Home-mode request can't actually complete
until the Home Bridge Bot exists (see `docs/architecture.md` §11).

**Before this will deploy or work**, two things beyond the usual `npm
install`/`deploy`:

1. **Bedrock model access.** In the [Bedrock console](https://console.aws.amazon.com/bedrock/),
   your target region → Model access → request access to the Claude model
   `infra/lib/ai-stack.ts` is configured for (Claude Sonnet 4.5 by default —
   see that file's `modelId` constant). This can take anywhere from
   instant to a short review period; nothing in Phase 2 works until it's
   granted.
2. **The exact model id.** `ai-stack.ts` hardcodes
   `anthropic.claude-sonnet-4-5-20250929-v1:0` (called via its `us.`
   cross-region inference profile) — this was the most precisely-documented
   current Claude model id I could confirm at the time this was written; it
   is NOT guaranteed to be the newest Claude model available in your
   account by the time you deploy. Check your Bedrock console's model
   catalog for the current model id you want (Claude Sonnet vs. the
   cheaper/faster Claude Haiku, and the exact dated version string), and
   either edit `ai-stack.ts`'s `modelId` constant or override
   `BEDROCK_MODEL_ID` — see `lambdas/ai-orchestrator/bedrock.ts`'s doc
   comment.

See `aws-backend/TESTING.md` §7 for how to exercise both request kinds via
CLI.

## Telegram bridge (Phase 3)

This round builds the **AWS half** of `mode: "home"`: `ai-orchestrator` now
dispatches a Home-mode request over Telegram instead of rejecting it with a
400, `telegram-webhook` finishes it when the reply comes back, `home-status`
reports presence, and `ai-request-timeout` gives up after ~2 minutes with no
reply. See `docs/architecture.md` §04 for the design and
`docs/api/README.md` for the three endpoints' contracts.

**This deliberately does not include the Home Bridge Bot** — the small
always-on process that would actually run at your house, long-poll
Telegram, and call Ollama. That's the next round of work, once this AWS
side is deployed and both bots below exist. Until then, a Home-mode request
will dispatch successfully (you'll see it hit the Cloud Relay Bot) and then
time out after ~2 minutes with no reply — that's expected, not a bug, given
nothing is listening on the other end yet.

### 1. Create two Telegram bots

Both are created the normal way, via [@BotFather](https://t.me/BotFather)
in Telegram: `/newbot`, pick a display name, pick a `@username` ending in
`bot`. Do this twice:

- **Cloud Relay Bot** — the one this AWS backend controls. Note its token
  (BotFather shows it once, right after creation — you'll need it in step
  3). Its username doesn't matter for this backend's own config.
- **Home Bridge Bot** — the one that will eventually run at your house.
  Note its **username** (not its token — this backend never touches this
  bot's token; only the future Home Bridge Bot process needs it).

### 2. Enable bot-to-bot communication on both

Telegram's direct bot-to-bot messaging is a May 2026 feature. **I could not
find a precisely-confirmed BotFather menu path for enabling it** at the
time this was written — it's new enough that documentation is thin. What's
confirmed (from Telegram's own bot-to-bot API docs, fetched directly, not
guessed): both bots need "Bot-to-Bot Communication Mode" turned on before
they can DM each other, and once enabled, one bot can message another via
`sendMessage` using the target bot's `@username` as `chat_id` — no
numeric bot id needed. Look for a bot-to-bot / "Allow messages from other
bots" toggle in BotFather's per-bot settings (`/mybots` → select each bot);
if you can't find it, BotFather's own `/help` or Telegram's bot-to-bot
announcement post are the most likely places it's documented by the time
you read this. **Do this for both bots** — the Cloud Relay Bot and the
Home Bridge Bot — before testing anything.

### 3. Store the Cloud Relay Bot's secrets

Two Secrets Manager secrets, created by hand (same reasoning as the FCM
service-account secret above — a bot token has no business in CDK synth
output or version control):

```powershell
# The Cloud Relay Bot's token from step 1
aws secretsmanager create-secret `
  --name todo-ai/telegram-bot-token `
  --secret-string "123456789:AAExampleTokenFromBotFather"

# A secret you make up yourself — a long random string, not from Telegram.
# This is what setWebhook (step 5) tells Telegram to send back on every
# webhook call, and what lambdas/telegram-webhook/index.ts checks against.
aws secretsmanager create-secret `
  --name todo-ai/telegram-webhook-secret `
  --secret-string "<a long random string you generate yourself>"
```

A reasonable way to generate that random string on Windows: `-join
((48..57)+(65..90)+(97..122)|Get-Random -Count 32|%{[char]$_})` in
PowerShell, or just use a password manager's generator (32+ characters,
alphanumeric — Telegram's `secret_token` parameter requires 1-256 characters
of `A-Za-z0-9_-`).

### 4. Set the Home Bridge Bot's username

Edit `infra/lib/telegram-stack.ts`'s `HOME_BRIDGE_BOT_USERNAME` constant to
the Home Bridge Bot's `@username` from step 1 (no leading `@`). Not a
secret — Telegram usernames are public — so this is plain source, the same
way `ai-stack.ts` hardcodes its Bedrock `modelId`.

### 5. Deploy, then point the Cloud Relay Bot's webhook at API Gateway

```bash
npm run deploy
```

This creates `TodoAi-Telegram`'s three Lambdas and adds `/telegram/webhook`
and `/home/status` to the existing HTTP API — check the `TodoAi-Api` stack's
`ApiUrl` output (same one `POST /todos` etc. already use). Then tell
Telegram to deliver updates there, using the **Cloud Relay Bot's** token and
the **webhook secret** from step 3 (not the same string — one is the bot's
own token, the other is the shared secret this backend checks):

```bash
curl "https://api.telegram.org/bot<CLOUD_RELAY_BOT_TOKEN>/setWebhook" \
  -d "url=<ApiUrl>/telegram/webhook" \
  -d "secret_token=<the todo-ai/telegram-webhook-secret value from step 3>"
```

A successful response is `{"ok":true,"result":true,...}`. `curl
"https://api.telegram.org/bot<CLOUD_RELAY_BOT_TOKEN>/getWebhookInfo"` shows
the currently-configured webhook and its recent delivery errors, if any —
useful once the Home Bridge Bot exists and you're debugging a real message
that didn't arrive.

### What's genuinely unverified here

**The entire Telegram wire protocol in `shared/telegram.ts` and the sender
check in `telegram-webhook/index.ts`** — both were written against
Telegram's own Bot API docs and its bot-to-bot feature page (fetched
directly and cited in those files' doc comments), not against real
bot-to-bot traffic, because there was no way to create two real bots and
exchange a message between them from this session. In particular: whether
`message.from.username` is reliably present on an inbound bot-to-bot
message the same way it would be for a human sender (`telegram-webhook`
depends on this to verify the sender), and whether chunked messages sent
back-to-back in a tight loop (`sendFrames`) hit any Telegram rate limit
this design doesn't already account for. Once both bots exist and you've
done step 2 above, sending one manual test message (even before the Home
Bridge Bot exists — see `aws-backend/TESTING.md`'s new Phase 3 section) is
the fastest way to find out whether either assumption was wrong; check
`aws logs tail /aws/lambda/todo-ai-telegram-webhook` if it doesn't show up
as expected.

## Deploying

Prerequisites: an AWS account with billing set up, the AWS CLI configured
(`aws configure`) with credentials for that account, and Bedrock model
access requested in your target region (see "AI planning & dialog" above —
needed starting Phase 2, not Phase 0, but request it now so it's not a
blocker later; see `docs/architecture.md` §10).

```bash
npm install
npm run build   # tsc --noEmit — typecheck everything first
npm test        # jest — unit tests for shared/
npx cdk bootstrap   # once per account/region
npm run synth    # sanity-check the generated CloudFormation
npm run deploy   # cdk deploy --all
```

After the first deploy, create your one Cognito user (see the comment at
the top of `infra/lib/auth-stack.ts` for the exact command) — self-sign-up
is off on purpose.

## About verification

I wrote this from a sandboxed session with **no access to the npm
registry** — neither the cloud container nor your linked machine could
reach `registry.npmjs.org` (blocked by network policy, confirmed both
ways). That means none of this was ever actually compiled, type-checked,
unit-tested, or run through `cdk synth`. I reviewed every file by hand
instead, but hand review is not the same as a compiler.

Concretely, before you deploy anything:

1. Run `npm install && npm run build && npm test && npx cdk synth` yourself
   first. If any of those fail, that's expected-possible, not a sign
   something is deeply wrong — tell me the error and I'll fix it.
2. ~~The one specific risk I'm least certain about...~~ **Update:** this
   already hit — `npm install` failed with `ETARGET` on
   `@aws-cdk/aws-apigatewayv2-alpha@2.150.0-alpha.0`, because that
   package (and its `-integrations`/`-authorizers` siblings) graduated
   into stable `aws-cdk-lib` back at v2.112.0, and the alpha packages
   stopped being published after `2.114.1-alpha.0` (Dec 2023) — well
   below the `2.150.0` pinned here. Fixed: `infra/lib/api-stack.ts` now
   imports `HttpApi`/`CorsHttpMethod`/`HttpMethod` from
   `aws-cdk-lib/aws-apigatewayv2`, `HttpLambdaIntegration` from
   `aws-cdk-lib/aws-apigatewayv2-integrations`, and
   `HttpUserPoolAuthorizer` from `aws-cdk-lib/aws-apigatewayv2-authorizers`;
   the three `@aws-cdk/*-alpha` entries are removed from `package.json`.
   Re-run `npm install` — it should resolve cleanly now. This was still
   never actually built or synthed on my end (same npm-registry block),
   so treat `build`/`test`/`synth` as still-unverified until you run them.
3. The push-notification follow-up (`lambdas/device-token/`,
   `lambdas/sns-platform-app-provider/`, `notifications-stack.ts`'s SNS
   wiring, `api-stack.ts`'s new `/device/token` route) is newer and
   equally unverified — same no-npm-registry constraint, plus it's the one
   piece of this backend I can't fully sanity-check by reading alone: the SNS
   `CreatePlatformEndpoint` "already exists" error-message-parsing path
   (`lambdas/device-token/index.ts`'s `createOrReuseEndpoint`) is based on
   documented AWS behavior, not something I've watched happen. If a
   redeploy after a token change throws instead of reusing the endpoint,
   that's the first place to look.
   **Update:** this already hit too — the original version of
   `notifications-stack.ts` used `new sns.CfnPlatformApplication(...)`,
   which doesn't exist: CloudFormation has no native
   `AWS::SNS::PlatformApplication` resource type at all (a real, still-open
   CDK gap — https://github.com/aws/aws-cdk/issues/36681), so there was
   never an L1 construct to call. Fixed by replacing it with a hand-written
   custom-resource Lambda (`lambdas/sns-platform-app-provider/`) wrapped in
   a CDK `Provider` — see that stack's doc comment for the full reasoning,
   including why the simpler generic `AwsCustomResource` helper wasn't
   used (it would have required passing the Firebase secret's unwrapped
   value through the Custom Resource's own properties, which defeats the
   point of Secrets Manager). Still never actually deployed on my end, same
   constraint as everything else here — if `cdk deploy` fails specifically
   on `FcmPlatformApplication`/`SnsPlatformAppProvider`, that custom
   resource is the first place to look, and `aws logs tail
   /aws/lambda/todo-ai-sns-platform-app-provider` will have the real error
   from inside the Lambda (CloudFormation's own error message for a failed
   custom resource is often just "see the details in CloudWatch Logs").
4. `lambdas/ai-orchestrator/` (Phase 2, this round) is unverified the same
   way — no compile, no `cdk synth`. See that lambda's own README ("About
   verification") for what was specifically checked against AWS's API
   reference and TypeScript type definitions before writing it (the
   Converse API's `toolConfig`/`toolChoice` shape, the field names on the
   response, the IAM permission pattern for a cross-region inference
   profile in `infra/lib/ai-stack.ts`) versus what's still resting on
   typical-usage understanding. The biggest real unknown isn't a guessed
   shape, though — it's whether `anthropic.claude-sonnet-4-5-20250929-v1:0`
   is still the model id you want by the time you deploy; see "AI planning
   & dialog" above.
   **Update:** this deployed and the Android app reached it successfully,
   but every request failed with `AccessDeniedException: Model access is
   denied due to IAM user or service role is not authorized to perform the
   required AWS Marketplace actions (aws-marketplace:ViewSubscriptions,
   aws-marketplace:Subscribe)`. Anthropic's models on Bedrock are
   distributed through AWS Marketplace, and the one-time "request model
   access" step in the Bedrock console only grants the *account* access —
   the *calling IAM identity* (here, `AiOrchestratorFn`'s execution role)
   separately needs `aws-marketplace:ViewSubscriptions`/`Subscribe` so
   Bedrock's "simplified model access" flow can create the marketplace
   agreement on first invoke; the `bedrock:InvokeModel`/`Converse` grants
   already in `ai-stack.ts` aren't sufficient on their own. Fixed by adding
   that statement to the Lambda's role in `ai-stack.ts` (unconditional
   `Resource: '*'` — marketplace subscription state isn't a Bedrock ARN, so
   it can't be scoped the way the InvokeModel/Converse grants above are).
   If you deployed before this fix and still see the error after
   redeploying, give it a few minutes — the knowledge-center writeups on
   this describe it as sometimes needing ~15 minutes to take effect after
   the marketplace agreement is first created.
   **Update:** confirmed the IAM statement alone isn't always enough to
   get unstuck, even once it's correctly deployed. Bedrock's "auto-create
   the marketplace agreement on first invoke" flow is the thing that's
   slow/flaky, not just the IAM propagation — AWS's own model-access page
   and multiple real reports of this exact error agree there are three
   independent things, and all three have to be true at once:
   (1) the account-level "request model access" step in the Bedrock
   console (already covered above, and it's a one-time per-account thing,
   not per-region — once granted in one region it applies everywhere the
   model is offered); (2) the calling role's `aws-marketplace:*` IAM grant
   (this section, fixed in `ai-stack.ts`); (3) an *actual* Marketplace
   subscription/agreement existing for that model, which Bedrock is
   supposed to create automatically the first time a correctly-permissioned
   identity invokes — but in practice that auto-create step is the one
   most likely to still be missing or still propagating even after (1) and
   (2) are both satisfied. If redeploying + waiting ~15 minutes doesn't
   clear it, create the agreement explicitly and skip waiting on
   auto-create, using your own AWS CLI credentials (not the Lambda's role
   — this is an account-level action, not something the Lambda itself
   needs to do):
   ```bash
   aws bedrock list-foundation-model-agreement-offers --model-id anthropic.claude-sonnet-4-5-20250929-v1:0
   # take the offerToken from that output, then:
   aws bedrock create-foundation-model-agreement --model-id anthropic.claude-sonnet-4-5-20250929-v1:0 --offer-token <offerToken>
   ```
   Sources: https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html,
   https://repost.aws/knowledge-center/bedrock-resolve-marketplace-permission,
   https://misaac.me/blog/aws-bedrock-access-denied-marketplace-actions/.
   **Update:** confirmed resolved — running the CLI agreement-creation
   commands above cleared it, so **AI planning & dialog is now
   confirmed working end to end** against the real AWS account, both the
   backend and the Android Planner/Dialog screens. The one wrinkle hit
   along the way: the account's AWS CLI was v2.11.10 (pre-dates Bedrock's
   GA, so `aws bedrock ...` wasn't recognized as a command at all, not an
   auth/permission error) — upgrading to a current AWS CLI v2 build fixed
   that separately from everything else in this item.
5. **Phase 3's AWS backend** (`lambdas/ai-orchestrator/`'s Home-mode
   dispatch, `lambdas/telegram-webhook/`, `lambdas/home-status/`,
   `lambdas/ai-request-timeout/`, `infra/lib/telegram-stack.ts`) is new
   this round and has not been deployed, synthed, or run at all — same
   no-npm-registry constraint as everything above, plus this one has no
   "Update" yet because it genuinely can't complete end to end until the
   Home Bridge Bot exists (next round). What I could and did check without
   deploying: `tsc --noEmit` against these files using a global TypeScript
   install once real dependencies (aws-cdk-lib, the AWS SDK v3 clients,
   zod) turned out to be unreachable via npm here too — that caught and
   fixed one real bug (`shared/dynamo.ts`'s `homeStatusKey` returned a
   widened `SK: string` where `HomeStatusItem` needs the literal
   `'HOME#status'`), though most of what that check reported was noise
   from the missing type packages themselves, not real errors. Also added
   `test/telegram.test.ts` (the chunking/reassembly arithmetic in
   `shared/telegram.ts` — the one piece of this round's logic that's pure
   enough to unit-test without mocking AWS) — written but, same
   no-npm-registry constraint, never actually run through `npm test`;
   run it yourself before trusting it passes. Also checked against
   primary sources rather than assumed: Telegram's own
   `sendMessage` and bot-to-bot API docs (cited in `shared/telegram.ts`'s
   doc comment) for the `chat_id: "@username"` and chunking behavior, and
   `lambdas/reminder-scan/`'s existing sparse-GSI1 pattern for
   `ai-request-timeout`'s "scan, act, clear the index" shape and
   `telegram-webhook`'s markDone/markFailed. What's still genuinely
   unverified: everything under "Telegram bridge (Phase 3)" → "What's
   genuinely unverified here" above, plus the ordinary "never actually
   deployed" risk every item above started with — run `npm run build`
   yourself before trusting any of this compiles.
6. **Chat-can-create-todos** (`shared/todo-item.ts`'s `buildTodoItem`
   extraction, `shared/ai-schema.ts`'s `aiCreateTodoSchema`/`createTodos`
   additions, `shared/ai-finalize.ts`'s `createRequestedTodos`, and the
   `ai-orchestrator`/`telegram-webhook` call sites — see
   `lambdas/ai-orchestrator/README.md`'s own new section) is new this
   round, same no-npm-registry constraint as everything above. Lower risk
   than most of this backend's past additions: no new AWS SDK surface at
   all — `createRequestedTodos` is a `PutCommand` against the same
   `TodoItem` shape `todo-api/index.ts` already writes via the now-shared
   `buildTodoItem`, and `buildTodoItem` itself is a pure extraction (moved,
   not rewritten) verified by re-reading `todo-api/index.ts` end to end
   afterward to confirm all three call sites (`createTodo`, `updateTodo`,
   `materializeNextOccurrence`) still behave identically. The one thing
   worth a real test rather than just a read: whether the model reliably
   leaves `createTodos` empty for ordinary conversation and only populates
   it when actually asked to add something — `CHAT_OUTPUT_JSON_SCHEMA`'s
   description field steers this, but a schema description is a strong
   hint to Bedrock's tool-use, not a hard constraint, so watch the first
   few real conversations for an unwanted todo appearing from an offhand
   mention rather than an actual "add a task" request.

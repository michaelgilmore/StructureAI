# Testing the Phase 0 deployment

Everything here uses the AWS CLI plus plain HTTP calls — no Android app
needed yet. Do it in order; each step feeds the next.

A note for PowerShell: the examples below use `Invoke-RestMethod` for
anything with a JSON body, not `curl.exe`. PowerShell's argument
marshalling to native executables can silently mangle a single-quoted JSON
string's embedded double quotes before `curl.exe` ever sees them — you'll
get a body like `{title:...}` with the quotes stripped, and the Lambda
fails with a JSON parse error that has nothing to do with your code.
`Invoke-RestMethod` with a hashtable/`ConvertTo-Json` body sidesteps that
entirely, so that's the pattern used throughout.

## 0. Get your stack outputs

After `cdk deploy --all` finishes, it prints outputs for each stack
(`UserPoolId`, `UserPoolClientId`, `ApiUrl`, `TableName`). If you didn't
capture them, fetch them again any time:

```powershell
aws cloudformation describe-stacks --stack-name TodoAi-Auth --query "Stacks[0].Outputs" --output table
aws cloudformation describe-stacks --stack-name TodoAi-Api --query "Stacks[0].Outputs" --output table
aws cloudformation describe-stacks --stack-name TodoAi-Data --query "Stacks[0].Outputs" --output table
```

Set them as variables for the rest of this doc (fill in your real values):

```powershell
$POOL_ID = "us-east-1_xxxxxxxxx"
$CLIENT_ID = "xxxxxxxxxxxxxxxxxxxxxxxxxx"
$API_URL = "https://xxxxxxxxxx.execute-api.us-east-1.amazonaws.com"
$TABLE = "TodoAi"
```

## 1. Create your one user

Self-sign-up is off on purpose (single-user app). Create the account by
hand, once:

The username can be anything (`mike`, `todoadmin`, whatever) — it doesn't
have to be your email. Email is still required as a separate attribute:

```powershell
aws cognito-idp admin-create-user `
  --user-pool-id $POOL_ID `
  --username mike `
  --user-attributes Name=email,Value=you@example.com Name=email_verified,Value=true `
  --message-action SUPPRESS

aws cognito-idp admin-set-user-password `
  --user-pool-id $POOL_ID `
  --username mike `
  --password "SomeStrongPassw0rd!" `
  --permanent
```

(`admin-set-user-password --permanent` skips the "force change password on
first login" state, which just gets in the way for an account only you use.)

## 2. Get a test JWT

The Android app will use SRP (no plaintext password over the wire). For
CLI testing, the app client also has `ADMIN_USER_PASSWORD_AUTH` enabled —
see the comment in `infra/lib/auth-stack.ts` — which lets you get a token
in one call:

```powershell
$AUTH = aws cognito-idp admin-initiate-auth `
  --user-pool-id $POOL_ID `
  --client-id $CLIENT_ID `
  --auth-flow ADMIN_USER_PASSWORD_AUTH `
  --auth-parameters USERNAME=mike,PASSWORD="SomeStrongPassw0rd!" `
  | ConvertFrom-Json

$ID_TOKEN = $AUTH.AuthenticationResult.IdToken
```

`IdToken` is what the HTTP API's Cognito authorizer checks — use that one,
not `AccessToken`. It's valid for 1 hour (see `idTokenValidity` in
`auth-stack.ts`); re-run this step when it expires.

## 3. Exercise the Todo API

```powershell
$headers = @{ Authorization = "Bearer $ID_TOKEN" }

# Create
$body = @{ title = "Water the plants"; priority = "low"; dueDate = "2026-09-01" } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri "$API_URL/todos" -Headers $headers -ContentType "application/json" -Body $body
$created
$id = $created.id

# List
Invoke-RestMethod -Uri "$API_URL/todos" -Headers $headers

# Get one
Invoke-RestMethod -Uri "$API_URL/todos/$id" -Headers $headers

# Update / complete
$patch = @{ status = "done" } | ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri "$API_URL/todos/$id" -Headers $headers -ContentType "application/json" -Body $patch

# Delete
Invoke-RestMethod -Method Delete -Uri "$API_URL/todos/$id" -Headers $headers
```

`$created` prints as a formatted object (not raw JSON) — that's just
`Invoke-RestMethod` deserializing the response for you; the API is still
returning JSON on the wire.

If you genuinely want `curl.exe` instead (e.g. matching commands you'll
reuse outside PowerShell), escape the inner double quotes with `` `" ``
rather than single-quoting the whole body:
```powershell
curl.exe -s -X POST "$API_URL/todos" -H "Authorization: Bearer $ID_TOKEN" -H "Content-Type: application/json" -d "{`"title`":`"Water the plants`",`"priority`":`"low`",`"dueDate`":`"2026-09-01`"}"
```
That works, but it's fragile to get exactly right by hand — prefer
`Invoke-RestMethod` unless you have a specific reason not to.

What to check: a 201-equivalent object with a generated `id` on create
(`Invoke-RestMethod` doesn't print the status code by default — it throws
on a non-2xx instead, so no error means it succeeded); the item shows up
in the `GET /todos` list; a request with no `Authorization` header (or a
stale token) gets rejected before it even reaches the Lambda — that's the
Cognito authorizer working, not the todo-api code.

## 4. Test recurrence

```powershell
$body = @{ title = "Take out trash"; dueDate = "2026-09-01"; recurrenceRule = "FREQ=WEEKLY" } | ConvertTo-Json
$recurring = Invoke-RestMethod -Method Post -Uri "$API_URL/todos" -Headers $headers -ContentType "application/json" -Body $body

$patch = @{ status = "done" } | ConvertTo-Json
$result = Invoke-RestMethod -Method Patch -Uri "$API_URL/todos/$($recurring.id)" -Headers $headers -ContentType "application/json" -Body $patch
$result.nextOccurrence
```

Check `$result.nextOccurrence.dueDate` is one week later than the
original. `GET /todos` should now show the *new* open todo; the completed
one still exists with `status: "done"`.

## 5. Test the reminder scan

The scan runs on its own every 5 minutes (see `notifications-stack.ts`),
but you don't have to wait:

1. Create a todo with a `reminderAt` a minute or two in the past:
   ```powershell
   $body = @{ title = "Test reminder"; reminderAt = "2026-08-30T00:00:00Z" } | ConvertTo-Json
   $reminderTodo = Invoke-RestMethod -Method Post -Uri "$API_URL/todos" -Headers $headers -ContentType "application/json" -Body $body
   ```
2. Invoke the Lambda directly instead of waiting for the schedule:
   ```powershell
   aws lambda invoke --function-name todo-ai-reminder-scan out.json
   Get-Content out.json
   ```
3. Check CloudWatch Logs for the run:
   ```powershell
   aws logs tail /aws/lambda/todo-ai-reminder-scan --since 5m
   ```
   You should see it find the todo and publish to SNS.
4. Confirm the DynamoDB side-effect — the item's `GSI1PK`/`GSI1SK` should
   be gone (so it won't fire again) and `reminderSentAt` set. Your Cognito
   `sub` is a claim in `$ID_TOKEN`, decodable without a library:
   ```powershell
   $claims = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($ID_TOKEN.Split('.')[1] + "=="))  | ConvertFrom-Json
   $sub = $claims.sub

   # Write the key to a file rather than fighting shell quoting on --key
   @{ PK = @{ S = "USER#$sub" }; SK = @{ S = "TODO#$($reminderTodo.id)" } } | ConvertTo-Json -Compress | Set-Content -Encoding utf8 key.json
   aws dynamodb get-item --table-name $TABLE --key file://key.json
   ```
5. To actually *see* the notification without installing the Android app,
   temporarily subscribe your own email to the SNS topic:
   ```powershell
   aws sns subscribe --topic-arn <SnsTopicArn output> --protocol email --notification-endpoint you@example.com
   ```
   Confirm the subscription email, then re-run step 1–2 with a fresh todo.
   You'll get the raw `default` message, not the `GCM` one (see §6) —
   unsubscribe when you're done poking at it.

## 6. Register a device and get a real push

Requires the Firebase service-account secret to already exist (see
`README.md`'s "Push notifications" section) and the Android app installed
and signed in — this is the one step in this doc you can't do from the CLI
alone.

1. Deploy (or redeploy) so `TodoAi-Notifications` and `TodoAi-Api` pick up
   `/device/token` and the SNS platform application.
2. Run the Android app and sign in. Registration now happens from
   `TodoRepository.refreshDeviceTokenRegistration`, called once per sign-in
   from `NavGraph.kt` — not from `TodoFcmService.onNewToken`, which
   normally fires only once per install (often *before* sign-in, when the
   `/device/token` call would 401) and won't fire again just because you
   signed in later. Either path logs the token at `Log.i`/`Log.d` under
   the `TodoAi.Fcm` / `TodoAi.TodoRepository` tags — filter Logcat for
   either. If you want to confirm server-side registration happened, call
   it by hand instead with the token from Logcat:
   ```powershell
   $body = @{ fcmToken = "<token from Logcat>" } | ConvertTo-Json
   Invoke-RestMethod -Method Post -Uri "$API_URL/device/token" -Headers $headers -ContentType "application/json" -Body $body
   ```
   Expect `{registered: true, changed: true}` the first time, and
   `{registered: true, changed: false}` if you re-run it with the same
   token.
3. Confirm the DynamoDB side-effect: a `DEVICE` row under your user
   (`aws dynamodb get-item` with the `key.json` trick from §5, but
   `SK = "DEVICE"` instead of `TODO#...`) with `endpointArn` and
   `subscriptionArn` populated.
4. Create a todo with a near-past `reminderAt` (§5 step 1) and either wait
   for the schedule or invoke `todo-ai-reminder-scan` directly (§5 step 2).
   You should get an actual push notification on the phone, **with the
   todo's title as its heading** — tapping it or checking
   `TodoFcmService.onMessageReceived`'s log output confirms the
   `data.todoId`/`title`/`body` shape came through. If a notification
   arrives but with generic/missing text instead of the title, that's the
   exact symptom of the `GCM` payload-shape bug fixed in
   `lambdas/reminder-scan/index.ts` (see that lambda's README, "About this
   fix") — redeploy and confirm you're not still running the pre-fix build.
5. To test re-registration (e.g. after a reinstall), just repeat step 2
   with a different token — `device-token`'s handler tears down the old
   SNS endpoint/subscription before creating the new one, so you shouldn't
   accumulate stale endpoints in the SNS console.

## 7. Test AI plan generation + dialog (Phase 2)

Requires Bedrock model access already granted for the model
`infra/lib/ai-stack.ts` is configured for, in the same region you deployed
to (see `README.md`'s "AI planning & dialog"). Uses the same `$headers`
from §3.

Generate a plan — create a couple of todos with near-term due dates first
(§3's `Water the plants` example works), then:

```powershell
$body = @{ kind = "plan"; mode = "cloud"; scope = "week" } | ConvertTo-Json
$plan = Invoke-RestMethod -Method Post -Uri "$API_URL/ai/request" -Headers $headers -ContentType "application/json" -Body $body
$plan.result
```

Expect `status: "done"` and a `result` with a `summary` string and an
`items` array where every `todoId` matches a todo you actually created —
if `items` is empty, that's expected when nothing's due this week, not a
bug.

Try a `focus` hint and a specific `periodStart`:

```powershell
$body = @{ kind = "plan"; mode = "cloud"; scope = "day"; periodStart = "2026-09-08"; focus = "keep it light, I'm traveling" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$API_URL/ai/request" -Headers $headers -ContentType "application/json" -Body $body
```

Start a dialog grounded in one todo, then continue the same conversation:

```powershell
$body = @{ kind = "chat"; mode = "cloud"; subjectType = "todo"; subjectId = $id; message = "when should I actually do this?" } | ConvertTo-Json
$chat = Invoke-RestMethod -Method Post -Uri "$API_URL/ai/request" -Headers $headers -ContentType "application/json" -Body $body
$chat.result.reply
$convoId = $chat.result.conversationId

$body2 = @{ kind = "chat"; mode = "cloud"; conversationId = $convoId; message = "why do you say that?" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$API_URL/ai/request" -Headers $headers -ContentType "application/json" -Body $body2
```

The second reply should read like it actually has the first exchange in
mind (referencing what it just said) — that's `history` in
`lambdas/ai-orchestrator/index.ts`'s `runChat` actually working, not just
two unrelated answers.

Poll a request by id (mostly useful for confirming the `PendingAiRequest`
side-effect exists — Cloud mode answers synchronously, so you don't need
this to get a result):

```powershell
Invoke-RestMethod -Uri "$API_URL/ai/request/$($plan.reqId)" -Headers $headers
```

**Update:** `mode: "home"` is no longer refused — Phase 3's AWS backend
(this round) dispatches it instead. See §8 below for testing that path;
§7's Cloud-mode tests above are unaffected.

What to check if a plan/chat request fails:
`aws logs tail /aws/lambda/todo-ai-ai-orchestrator --since 10m` — a
`ValidationException` mentioning the model id usually means Bedrock model
access hasn't been granted yet for that model in this region (or the
model id in `infra/lib/ai-stack.ts` no longer matches what's in your
account's catalog); an `AccessDeniedException` mentioning
`inference-profile` or `foundation-model` usually means the IAM policy in
`ai-stack.ts` and the actual model id have drifted apart — redeploy after
confirming the id, since the IAM resource ARNs are derived from it.

## 8. Test the Telegram bridge (Phase 3 backend)

Requires both Telegram bots created, bot-to-bot mode enabled on both, the
two Secrets Manager secrets in place, `HOME_BRIDGE_BOT_USERNAME` edited,
and the Cloud Relay Bot's webhook pointed at your `ApiUrl` — see
`README.md`'s "Telegram bridge (Phase 3)" section for all of that. **The
Home Bridge Bot itself doesn't exist yet**, so nothing below can complete
end to end — this section is about confirming the AWS side dispatches and
records things correctly, not about getting a real AI reply back yet.

### 8a. Confirm a Home-mode request dispatches

```powershell
$body = @{ kind = "plan"; mode = "home"; scope = "day" } | ConvertTo-Json
$req = Invoke-RestMethod -Method Post -Uri "$API_URL/ai/request" -Headers $headers -ContentType "application/json" -Body $body
$req
# Expect: { reqId, status: "sent", kind: "plan" } — no `result` yet, unlike Cloud mode.
```

`aws logs tail /aws/lambda/todo-ai-ai-orchestrator --since 5m` should show
no errors — a `missing required env var TELEGRAM_BOT_TOKEN_SECRET_ARN` or
`HOME_BRIDGE_BOT_USERNAME` means `infra/lib/telegram-stack.ts` didn't wire
into `aiOrchestratorFn` (check `bin/todo-ai.ts`'s stack ordering); a
Telegram API error in the logs (`sendTelegramMessage failed: ...`) usually
means the Cloud Relay Bot's token in Secrets Manager is wrong, or
bot-to-bot mode isn't actually enabled on one of the two bots yet.

Poll it and confirm it eventually times out (~2 minutes, since nothing is
listening yet):

```powershell
Start-Sleep -Seconds 130
Invoke-RestMethod -Uri "$API_URL/ai/request/$($req.reqId)" -Headers $headers
# Expect: { reqId, status: "failed", error: "Home Ollama didn't respond in time" }
```

If it's still `"sent"` after that, check
`aws logs tail /aws/lambda/todo-ai-ai-request-timeout --since 5m` — this
Lambda runs on a 1-minute EventBridge schedule, so give it up to a minute
past the 2-minute mark before concluding something's wrong, and confirm
the schedule rule (`todo-ai-ai-request-timeout-schedule`) actually exists
and is enabled.

### 8b. Confirm the webhook validates and rejects correctly

Without a real Home Bridge Bot to send a valid frame, you can still confirm
`telegram-webhook`'s auth check works — this hits the webhook URL directly,
not through Telegram:

```powershell
# Wrong/missing secret token → expect 401
Invoke-WebRequest -Method Post -Uri "$ApiUrl/telegram/webhook" -Body '{}' -ContentType "application/json"

# Correct secret token, but a body with no `message` → expect 200 (ignored, not an error)
Invoke-WebRequest -Method Post -Uri "$ApiUrl/telegram/webhook" -ContentType "application/json" `
  -Headers @{ "X-Telegram-Bot-Api-Secret-Token" = "<your todo-ai/telegram-webhook-secret value>" } `
  -Body '{"update_id": 1}'
```

### 8c. Once both bots can actually message each other

Before the Home Bridge Bot exists, you can still confirm Telegram's
bot-to-bot mode itself is working (this is the piece flagged as unverified
in `README.md`): from a small script or even `curl` using the **Home
Bridge Bot's** token, send the Cloud Relay Bot a plain text message via
`sendMessage` with `chat_id` set to the Cloud Relay Bot's `@username`. If
bot-to-bot mode is correctly enabled on both, `getWebhookInfo` on the Cloud
Relay Bot (see `README.md`) should show a recent successful delivery, and
`aws logs tail /aws/lambda/todo-ai-telegram-webhook --since 5m` should show
it was received and (correctly) ignored as an unrecognized frame — since a
hand-typed plain-text message isn't valid JSON, this exercises the
"message text was not a valid frame, ignoring" path rather than a real
`ai_request`/`ai_result` round trip, but it's real confirmation that the
two bots can reach each other at all before building the Home Bridge Bot
against that assumption.

## If something doesn't work

- 401/403 on API calls → token expired (re-run step 2) or missing the
  `Bearer ` prefix.
- Lambda errors → `aws logs tail /aws/lambda/todo-ai-todo-api --since 10m`
  (or `todo-ai-reminder-scan` / `todo-ai-device-token`) for the actual
  stack trace.
- Nothing shows up in DynamoDB at all → double check `$TABLE` and that
  you're looking in the same AWS region you deployed to.
- `TodoAi-Notifications` fails to deploy with a secret-not-found-shaped
  error → you haven't created the `todo-ai/fcm-service-account` secret yet
  (§"Push notifications" in `README.md`).
- `/device/token` succeeds but no push ever arrives → check the SNS
  console for the platform endpoint's `Enabled` attribute (FCM sometimes
  disables an endpoint after repeated delivery failures — a stale/expired
  token is the usual cause; re-registering with a fresh token from Logcat
  fixes it).

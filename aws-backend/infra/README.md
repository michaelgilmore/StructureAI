# infra

CDK stack definitions (TypeScript). The CDK app itself is rooted one level
up — `../package.json`, `../cdk.json`, and `../bin/todo-ai.ts` (the entry
point that instantiates all four stacks below).

| Stack | File | Provisions |
|---|---|---|
| `TodoAi-Data` | `lib/data-stack.ts` | The DynamoDB table + sparse `GSI1` reminder index |
| `TodoAi-Auth` | `lib/auth-stack.ts` | Cognito user pool + app client (self-sign-up off) |
| `TodoAi-Notifications` | `lib/notifications-stack.ts` | SNS reminder topic + the `reminder-scan` Lambda on an EventBridge schedule |
| `TodoAi-Api` | `lib/api-stack.ts` | HTTP API (Cognito-authorized) + the `todo-api` Lambda |

See [`../../docs/architecture.md` §05](../../docs/architecture.md#05--aws-backend)
for the design this implements, and `../README.md` for deploy steps and —
important — what hasn't been verified yet.

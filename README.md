# Structure

A todo manager that plans your day, week, month, and year with you — running on
Amazon Bedrock when you're anywhere, or on your own Ollama box at home, reached
over Telegram, when you'd rather keep it local.

**Status:** Phase 0 (Cognito auth, Todo CRUD API, reminder scan), Phase 1
(`android-app/` — auth, todo CRUD, recurrence/reminders, offline sync, push
notifications, Settings screen), and Phase 2 (Bedrock-backed plan
generation + dialog, Cloud mode only) are all deployed/built and
**confirmed working end to end** against the real AWS account — backend,
the Android Planner/Dialog screens, the Settings screen, and push
notifications (a real device test: create a reminder, get the actual
notification) — see
[`aws-backend/lambdas/ai-orchestrator/README.md`](aws-backend/lambdas/ai-orchestrator/README.md),
[`aws-backend/README.md`](aws-backend/README.md#push-notifications-phase-1-follow-up),
and [`android-app/README.md`](android-app/README.md#about-verification)
for the real bugs that surfaced (and were fixed) getting there — including
a device-registration-timing bug in the push notification path. AI mode
(Cloud/Home) is now persisted via DataStore as groundwork for Phase 3, but
Home mode itself still isn't selectable — see
[`docs/architecture.md`](docs/architecture.md) §08.

Phase 3's **AWS backend half is now built** — `ai-orchestrator` dispatches
`mode: "home"` requests over Telegram, `telegram-webhook` finishes them,
`home-status` and `ai-request-timeout` round it out — but not yet deployed
or tested, and it can't complete a Home-mode request end to end until the
Home Bridge Bot (the process that runs at your house) exists, which is
next. See [`aws-backend/README.md`](aws-backend/README.md#telegram-bridge-phase-3)
for setup and status.

See [`docs/architecture.md`](docs/architecture.md) for the full design and
roadmap.

## Layout

```
todo-ai/
├── docs/                  Documentation — start here
│   ├── architecture.md    The living design doc (system, data model, roadmap)
│   ├── decisions/         One file per real decision (ADRs)
│   └── api/                REST API contract, once it exists (Phase 0)
├── android-app/           Kotlin / Jetpack Compose client            (Phase 1+)
├── aws-backend/           CDK infra + Lambda functions               (Phase 0+)
│   ├── infra/              CDK stacks (API Gateway, DynamoDB, Cognito, …)
│   ├── lambdas/             One folder per function
│   │   ├── todo-api/            Todo CRUD
│   │   ├── ai-orchestrator/     Plan + chat requests, Bedrock or Telegram relay
│   │   ├── telegram-webhook/    Cloud Relay Bot — Telegram → AWS
│   │   ├── home-status/         Home Ollama presence for the app
│   │   ├── ai-request-timeout/  Gives up on a stuck Home-mode request (~2 min)
│   │   └── reminder-scan/       EventBridge-triggered due-date scan
│   └── shared/              Types/schemas shared across lambdas
├── home-bridge-bot/       Standalone service that runs at your house  (Phase 3)
│                           — polls Telegram, calls local Ollama, never
│                             accepts an inbound connection
└── scripts/                Local dev / deploy helper scripts
```

## Where things run

| Piece | Runs | Phase |
|---|---|---|
| `android-app/` | Your phone | 1 |
| `aws-backend/` | AWS (serverless) | 0 (API/data), 2 (Bedrock), 3 (Telegram webhook) |
| `home-bridge-bot/` | Your home machine, next to Ollama | 3 |

## Roadmap

0. **AWS foundation** — CDK skeleton, Cognito, DynamoDB, Todo CRUD API, reminder scan. *Deployed and verified.*
1. **Android app skeleton** — auth, todo list/detail, recurrence & reminders, offline queue. *Built and running.*
2. **Cloud AI** — Bedrock-backed plan generation and dialog. *Backend and Android Planner/Dialog screens both deployed/built and confirmed working end to end.*
3. **Telegram bridge** — Cloud Relay Bot, Home Bridge Bot, Ollama integration, presence heartbeat. *AWS backend half built, not yet deployed/tested; Home Bridge Bot not yet built.*
4. **Polish** — widgets, offline conflict resolution, bigger home model.

Full detail, diagrams, and the open question on this round: [`docs/architecture.md`](docs/architecture.md).

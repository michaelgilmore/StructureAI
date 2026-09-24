# shared

Types and validation logic used by both `infra/` (bundled into the lambdas
via esbuild) and `test/`, so the contract described in
[`../../docs/architecture.md`](../../docs/architecture.md) lives in one
place instead of drifting per-function.

- `types.ts` — the `Todo` domain type and its DynamoDB item shape.
- `todo-schema.ts` — zod schemas for create/update input validation.
- `recurrence.ts` — the RRULE-subset parser and next-occurrence /
  reminder-shift calculators (see
  `../../docs/decisions/0004-recurrence-and-reminders-in-v1.md`).
- `dynamo.ts` — a shared `DynamoDBDocumentClient` and key-building helpers.

`Plan`, `Conversation`, `PendingAiRequest`, and `HomeStatus` types join
`types.ts` in Phase 2/3 when those features are built.

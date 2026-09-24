# todo-api

Implemented (Phase 0). `GET/POST/PATCH/DELETE /todos[/{id}]`, one Lambda
routed internally on API Gateway's `routeKey`. Every item is scoped to the
caller's Cognito `sub` — see `index.ts` for the full CRUD logic, including
recurrence materialization when a recurring todo is marked done.

See [`../../../docs/architecture.md` §06](../../../docs/architecture.md#06--data-model)
for the data model and `../../../docs/api/README.md` for the endpoint list.

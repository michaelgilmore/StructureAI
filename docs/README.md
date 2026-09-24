# Documentation

- [`architecture.md`](architecture.md) — the living system design: architecture, data model, AI features, security, and the phase roadmap. Start here.
- [`decisions/`](decisions) — one short file per decision that would otherwise get re-litigated in a PR comment thread six months from now.
- [`api/`](api) — the REST API contract (OpenAPI), once Phase 0 defines one.

When the design changes, edit `architecture.md` in the same PR as the code
change it describes. If a change reverses or narrows an existing decision,
add a new numbered file to `decisions/` rather than editing history.

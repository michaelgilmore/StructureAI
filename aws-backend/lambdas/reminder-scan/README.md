# reminder-scan

Implemented (Phase 0). EventBridge-triggered every 5 minutes (see
`../../infra/lib/notifications-stack.ts`). Queries the sparse
`GSI1PK=REMINDER` index for anything due, publishes one SNS message per
todo, then clears the index entry.

A real mobile-push subscriber now exists (Phase 1 follow-up — see
`../device-token/README.md` and `../../README.md`'s "Push notifications"
section) — see `index.ts`'s top doc comment for the FCM v1 payload shape
`publishReminder` sends.

## About this fix

**Real bug, found and fixed:** the push notification was arriving on the
phone with generic text — no todo title — instead of `item.title` as its
heading. Root cause: `publishReminder`'s `GCM` payload was the older, flat
legacy-GCM shape (`{data: {...}}`), but this project's SNS platform
application authenticates via a Firebase *service account*
(`notifications-stack.ts`'s `FcmPlatformApplication`), which means it's
registered against FCM's newer HTTP v1 API — and SNS requires a
genuinely different, more deeply nested payload shape for a v1 platform
application: `{fcmV1Message: {message: {data: {...}}}}` (confirmed against
AWS's own current docs,
https://docs.aws.amazon.com/sns/latest/dg/sns-fcm-v1-payloads.html, not
just remembered). The flat shape doesn't error at `Publish` time (SNS only
checks that it's valid JSON there) — it fails later and silently from this
Lambda's point of view, when SNS's actual delivery attempt to FCM's v1 REST
endpoint gets rejected for a body FCM doesn't recognize. Fixed by nesting
the same `data` object inside `fcmV1Message.message`.

This also means the earlier "confirmed end to end on a real device" note in
`../../README.md`'s "Push notifications" section was testing the *delivery
plumbing* (token registration, subscription, a push arriving at all) — real
and correctly verified — but not the payload's actual content, which is
where this bug was hiding. **Not yet re-verified against a real device**
after this fix — redeploy (`npm run deploy`) and check that the next due
reminder shows the todo's title, not just that a notification arrives at
all.

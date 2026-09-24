# device-token

Implemented as of the Phase 1 push-notification follow-up (see
`../../../android-app/README.md`, "What doesn't work end-to-end yet" — this
is what closes that gap). `POST /device/token`, Cognito-authenticated.
Registers the caller's FCM token as an SNS platform endpoint and subscribes
it to the reminder topic, so `../reminder-scan`'s publishes actually reach
the phone.

One device per user (see `shared/dynamo.ts`'s `deviceKey`) — this is a
single-user, single-phone app today; registering a new token tears down
the previous endpoint/subscription rather than accumulating them.

Requires the SNS platform application `../../infra/lib/notifications-stack.ts`
provisions (via `../sns-platform-app-provider/`, a custom-resource Lambda —
CloudFormation has no native resource type for this, see that stack's doc
comment) to exist, which requires a Firebase service-account secret in
Secrets Manager that **you create by hand, outside CDK** — see
`../../README.md`'s "Push notification setup" for the exact steps. Without
that secret, this stack won't deploy.

See [`../../../docs/architecture.md` §05](../../../docs/architecture.md#05--aws-backend).

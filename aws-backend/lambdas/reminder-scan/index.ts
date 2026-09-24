import { PublishCommand, SNSClient } from '@aws-sdk/client-sns';
import { QueryCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { ddb, TABLE_NAME } from '../../shared/dynamo';
import type { TodoItem } from '../../shared/types';

const sns = new SNSClient({});
const TOPIC_ARN = process.env.TOPIC_ARN ?? '';

/**
 * EventBridge-scheduled (every 5 minutes — see infra/lib/notifications-stack.ts).
 * Queries the sparse GSI1=REMINDER index for anything due, publishes one
 * SNS message per todo, then clears the index entry so it isn't re-fired
 * on the next tick. See docs/architecture.md §04 ("Knowing whether Home is
 * actually up" is the sibling mechanism — this is the reminder path, not
 * the presence one) and §05.
 *
 * The topic can now have a real mobile-push subscriber (see
 * lambdas/device-token/index.ts and infra/lib/notifications-stack.ts) —
 * publishReminder below sends a structured per-protocol payload
 * (MessageStructure: 'json') so that subscriber gets an FCM `data` payload
 * shaped the way android-app's TodoFcmService expects it
 * (data.todoId/title/body), rather than SNS's default plain-text wrapping.
 * The `default` key still covers any other protocol subscribed to the
 * topic (e.g. the email subscription from aws-backend/TESTING.md §5).
 *
 * The `GCM` key's JSON has to be wrapped in `{fcmV1Message: {message: ...}}`
 * — this platform application authenticates with a Firebase *service
 * account* (see notifications-stack.ts's doc comment on `FcmPlatformApplication`),
 * i.e. FCM's newer HTTP v1 API, and SNS's required payload shape for a v1
 * platform application is genuinely different from the flat
 * `{data: {...}, notification: {...}}` shape the older legacy-GCM API used
 * — see https://docs.aws.amazon.com/sns/latest/dg/sns-fcm-v1-payloads.html.
 * A flat (unwrapped) payload isn't rejected at Publish time (it's still
 * valid JSON, which is all SNS checks synchronously) — it fails later,
 * silently from this Lambda's perspective, when SNS actually calls FCM's
 * v1 REST endpoint with a body FCM doesn't recognize (a documented FCM v1
 * error for a missing `message` wrapper). This file previously sent the
 * flat shape — see this lambda's README ("About this fix") for the
 * real-world symptom that surfaced it: a reminder push arriving with
 * generic/no text instead of the todo's title.
 */
export const handler = async (): Promise<{ due: number }> => {
  const nowIso = new Date().toISOString();

  const due = await ddb.send(new QueryCommand({
    TableName: TABLE_NAME,
    IndexName: 'GSI1',
    KeyConditionExpression: 'GSI1PK = :pk AND GSI1SK <= :now',
    ExpressionAttributeValues: { ':pk': 'REMINDER', ':now': nowIso },
  }));

  const items = (due.Items ?? []) as TodoItem[];
  console.log(`reminder-scan: ${items.length} due at ${nowIso}`);

  for (const item of items) {
    await publishReminder(item);
    await clearReminderIndex(item, nowIso);
  }

  return { due: items.length };
};

async function publishReminder(item: TodoItem): Promise<void> {
  const body = item.dueDate ? `Due ${item.dueDate}` : 'Reminder';

  await sns.send(new PublishCommand({
    TopicArn: TOPIC_ARN,
    MessageStructure: 'json',
    Message: JSON.stringify({
      // Fallback for any non-mobile-push subscriber (e.g. a plain email
      // subscription used for manual testing — see TESTING.md §5).
      default: `${item.title} — ${body}`,
      // SNS still calls this key "GCM" even for FCM's newer v1 API (see
      // infra/lib/notifications-stack.ts), but the *value* has to be the
      // v1-specific `{fcmV1Message: {message: {data: {...}}}}` shape — see
      // this file's top doc comment. `data` (not `notification`) is what
      // keeps this a data-only message, so TodoFcmService.onMessageReceived
      // always runs it through NotificationHelper.showReminder itself
      // rather than the OS auto-displaying a bare-bones tray notification
      // when the app's backgrounded — and what
      // TodoFcmService.onMessageReceived reads via remoteMessage.data.
      GCM: JSON.stringify({
        fcmV1Message: {
          message: {
            data: {
              todoId: item.id,
              title: item.title,
              body,
            },
          },
        },
      }),
    }),
    MessageAttributes: {
      userId: { DataType: 'String', StringValue: item.userId },
    },
  }));
}

/** Removes GSI1PK/GSI1SK so this item drops out of the sparse index — it won't fire again. */
async function clearReminderIndex(item: TodoItem, sentAtIso: string): Promise<void> {
  await ddb.send(new UpdateCommand({
    TableName: TABLE_NAME,
    Key: { PK: item.PK, SK: item.SK },
    UpdateExpression: 'REMOVE GSI1PK, GSI1SK SET reminderSentAt = :sentAt',
    ExpressionAttributeValues: { ':sentAt': sentAtIso },
  }));
}

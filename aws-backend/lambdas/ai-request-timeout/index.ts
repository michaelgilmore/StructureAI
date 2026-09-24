import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { PublishCommand, SNSClient } from '@aws-sdk/client-sns';
import { UpdateCommand, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { ddb, TABLE_NAME } from '../../shared/dynamo';
import type { PendingAiRequestItem } from '../../shared/types';

const sns = new SNSClient({});
const TOPIC_ARN = process.env.TOPIC_ARN ?? '';

/** Matches the ~2 minute end-to-end budget ../../docs/architecture.md §04's sequence diagram documents for a Home-mode round trip (Telegram hop both ways + local Ollama inference). */
const TIMEOUT_MS = 2 * 60 * 1000;

const TIMEOUT_ERROR = "Home Ollama didn't respond in time";

/**
 * EventBridge-scheduled (every ~1 minute — see ../../infra/lib/telegram-stack.ts).
 * Scans the sparse GSI1=PENDING_HOME_REQUEST index — the same GSI1
 * ../reminder-scan/ already uses, with a different discriminator value; see
 * ../../shared/types.ts's doc comment on `PendingAiRequestItem` — for
 * anything dispatched more than ~2 minutes ago and still `status='sent'`,
 * marks it failed, clears the index entry, and pushes exactly the
 * notification ../../docs/architecture.md §04's sequence diagram calls for:
 * "Home Ollama didn't respond — retry in Cloud mode?"
 *
 * The update is conditioned on the item still being `status='sent'` (see
 * failIfStillPending) to close a real race: ../telegram-webhook/ could
 * resolve the same request (a late-but-real reply, or a duplicate delivery)
 * in the window between this Lambda's Query and its Update. Losing that
 * race is expected and silent, not an error — it means the request finished
 * on its own just in time, so there is nothing to time out.
 */
export const handler = async (): Promise<{ timedOut: number; alreadyResolved: number }> => {
  const cutoff = new Date(Date.now() - TIMEOUT_MS).toISOString();

  const stuck = await ddb.send(new QueryCommand({
    TableName: TABLE_NAME,
    IndexName: 'GSI1',
    KeyConditionExpression: 'GSI1PK = :pk AND GSI1SK <= :cutoff',
    ExpressionAttributeValues: { ':pk': 'PENDING_HOME_REQUEST', ':cutoff': cutoff },
  }));

  const items = (stuck.Items ?? []) as PendingAiRequestItem[];
  console.log(`ai-request-timeout: ${items.length} candidate(s) older than ${cutoff}`);

  let timedOut = 0;
  let alreadyResolved = 0;
  for (const item of items) {
    const didTimeOut = await failIfStillPending(item);
    if (didTimeOut) {
      timedOut++;
      await pushTimeoutNotification(item.userId);
    } else {
      alreadyResolved++;
    }
  }

  return { timedOut, alreadyResolved };
};

/** Returns true if this call actually marked the item failed; false if it lost the race described in the doc comment above (item was no longer 'sent'). */
async function failIfStillPending(item: PendingAiRequestItem): Promise<boolean> {
  try {
    await ddb.send(new UpdateCommand({
      TableName: TABLE_NAME,
      Key: { PK: item.PK, SK: item.SK },
      UpdateExpression: 'SET #status = :failed, #error = :err REMOVE GSI1PK, GSI1SK',
      ConditionExpression: '#status = :sent',
      ExpressionAttributeNames: { '#status': 'status', '#error': 'error' },
      ExpressionAttributeValues: { ':failed': 'failed', ':sent': 'sent', ':err': TIMEOUT_ERROR },
    }));
    return true;
  } catch (err) {
    if (err instanceof ConditionalCheckFailedException) {
      console.log('ai-request-timeout: request resolved just before timeout fired, skipping', { reqId: item.reqId });
      return false;
    }
    throw err;
  }
}

async function pushTimeoutNotification(userId: string): Promise<void> {
  const title = 'Home AI timed out';
  const body = "Home Ollama didn't respond — retry in Cloud mode?";
  await sns.send(new PublishCommand({
    TopicArn: TOPIC_ARN,
    MessageStructure: 'json',
    Message: JSON.stringify({
      default: `${title} — ${body}`,
      // No todoId — same rationale as ../telegram-webhook/'s
      // pushAiResultNotification: the current Android app already treats a
      // missing todoId as "just open the app," not an error.
      // fcmV1Message/message wrapper required for this project's FCM v1
      // platform application — see ../reminder-scan/index.ts's top doc
      // comment for why the flatter {data: {...}} shape silently fails.
      GCM: JSON.stringify({ fcmV1Message: { message: { data: { title, body } } } }),
    }),
    MessageAttributes: { userId: { DataType: 'String', StringValue: userId } },
  }));
}

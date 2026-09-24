import type { APIGatewayProxyEventV2WithJWTAuthorizer, APIGatewayProxyResultV2 } from 'aws-lambda';
import {
  CreatePlatformEndpointCommand,
  DeleteEndpointCommand,
  SNSClient,
  SubscribeCommand,
  UnsubscribeCommand,
} from '@aws-sdk/client-sns';
import { GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';
import { z, ZodError } from 'zod';
import { ddb, deviceKey, TABLE_NAME } from '../../shared/dynamo';

const sns = new SNSClient({});
const PLATFORM_APPLICATION_ARN = process.env.PLATFORM_APPLICATION_ARN ?? '';
const TOPIC_ARN = process.env.TOPIC_ARN ?? '';

const requestSchema = z.object({ fcmToken: z.string().min(1) });

interface DeviceItem {
  PK: string;
  SK: string;
  entityType: 'DEVICE';
  userId: string;
  fcmToken: string;
  endpointArn: string;
  subscriptionArn: string;
  updatedAt: string;
}

/**
 * POST /device/token — registers this device's FCM token so reminder-scan's
 * SNS publishes (see ../reminder-scan/index.ts) actually reach the phone as
 * a push notification, closing the gap Phase 0 deliberately left open (see
 * ../../README.md "What Phase 0 actually built"). One device per user (see
 * shared/dynamo.ts's deviceKey) — registering a new token replaces the old
 * endpoint/subscription rather than accumulating them, which is correct
 * for "you, one phone" and would need revisiting for multiple devices.
 *
 * Steps, all idempotent-ish (see comments below): create (or reuse) an SNS
 * platform endpoint for the token, subscribe it to the reminder topic, and
 * remember both ARNs so next time we can tell "same token, nothing to do"
 * from "token changed, tear down the old registration first".
 */
export const handler = async (
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
): Promise<APIGatewayProxyResultV2> => {
  const userId = event.requestContext.authorizer.jwt.claims.sub as string;

  try {
    const raw = event.body ?? '{}';
    const text = event.isBase64Encoded ? Buffer.from(raw, 'base64').toString('utf8') : raw;
    const { fcmToken } = requestSchema.parse(text.trim().length ? JSON.parse(text) : {});

    const existing = (await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: deviceKey(userId) }))).Item as
      | DeviceItem
      | undefined;

    if (existing?.fcmToken === fcmToken) {
      // Same device, same token — nothing to do server-side. Still touch
      // updatedAt so you can tell from the item when it was last confirmed.
      await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: { ...existing, updatedAt: new Date().toISOString() } }));
      return json(200, { registered: true, changed: false });
    }

    if (existing) {
      await teardown(existing);
    }

    const endpointArn = await createOrReuseEndpoint(fcmToken, userId);
    const subscriptionArn = await subscribeEndpoint(endpointArn);

    const item: DeviceItem = {
      ...deviceKey(userId),
      entityType: 'DEVICE',
      userId,
      fcmToken,
      endpointArn,
      subscriptionArn,
      updatedAt: new Date().toISOString(),
    };
    await ddb.send(new PutCommand({ TableName: TABLE_NAME, Item: item }));

    return json(200, { registered: true, changed: true });
  } catch (err) {
    if (err instanceof ZodError) {
      return json(400, { error: 'validation failed', details: err.issues });
    }
    console.error('device-token error', err);
    return json(500, { error: 'internal error' });
  }
};

/**
 * SNS's CreatePlatformEndpoint is idempotent for the exact same
 * token+attributes — it just returns the existing ARN. It only throws when
 * an endpoint already exists for that token with *different* attributes
 * (we don't set any custom attributes here, but a stale endpoint from
 * before this Lambda existed, or a previous CustomUserData value, could
 * still trigger it) — the ARN is embedded in the error message, which is
 * the only place AWS exposes it for this specific error.
 */
async function createOrReuseEndpoint(fcmToken: string, userId: string): Promise<string> {
  try {
    const result = await sns.send(new CreatePlatformEndpointCommand({
      PlatformApplicationArn: PLATFORM_APPLICATION_ARN,
      Token: fcmToken,
      CustomUserData: userId,
    }));
    if (!result.EndpointArn) throw new Error('SNS did not return an EndpointArn');
    return result.EndpointArn;
  } catch (err) {
    const message = err instanceof Error ? err.message : '';
    const match = message.match(/arn:aws:sns:[^ "]+/);
    if (match) return match[0];
    throw err;
  }
}

async function subscribeEndpoint(endpointArn: string): Promise<string> {
  const result = await sns.send(new SubscribeCommand({
    TopicArn: TOPIC_ARN,
    Protocol: 'application',
    Endpoint: endpointArn,
  }));
  if (!result.SubscriptionArn) throw new Error('SNS did not return a SubscriptionArn');
  return result.SubscriptionArn;
}

/** Best-effort cleanup of a device's previous registration — failures here shouldn't block registering the new one. */
async function teardown(existing: DeviceItem): Promise<void> {
  await Promise.allSettled([
    sns.send(new UnsubscribeCommand({ SubscriptionArn: existing.subscriptionArn })),
    sns.send(new DeleteEndpointCommand({ EndpointArn: existing.endpointArn })),
  ]);
}

function json(statusCode: number, body: unknown): APIGatewayProxyResultV2 {
  return { statusCode, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) };
}

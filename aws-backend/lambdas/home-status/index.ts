import type { APIGatewayProxyEventV2WithJWTAuthorizer, APIGatewayProxyResultV2 } from 'aws-lambda';
import { GetCommand } from '@aws-sdk/lib-dynamodb';
import { ddb, TABLE_NAME, homeStatusKey } from '../../shared/dynamo';
import type { HomeStatusItem } from '../../shared/types';

/**
 * `GET /home/status` — Cognito-authenticated. See
 * ../../docs/architecture.md §04, "Knowing whether Home is actually up": a
 * plain DynamoDB read, no Telegram round trip. The Home Bridge Bot
 * heartbeats roughly every 90s (../telegram-webhook/'s handleHeartbeat is
 * what actually records each one into `HomeStatus`); this just reads the
 * last one and decides online/offline from how stale it is.
 */
const STALE_AFTER_SECONDS = 3 * 90; // ~3 missed heartbeats' worth of slack before calling it offline, not just one late beacon

export const handler = async (
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
): Promise<APIGatewayProxyResultV2> => {
  const userId = event.requestContext.authorizer.jwt.claims.sub as string;

  const result = await ddb.send(new GetCommand({ TableName: TABLE_NAME, Key: homeStatusKey(userId) }));
  const item = result.Item as HomeStatusItem | undefined;

  if (!item) {
    // No heartbeat ever recorded — either the Home Bridge Bot has never
    // run, or Telegram/webhook wiring isn't set up yet. Same shape as the
    // "definitely offline" case below, just without a timestamp to show.
    return json(200, { online: false });
  }

  const ageSeconds = (Date.now() - Date.parse(item.lastHeartbeatAt)) / 1000;
  // "online" requires both a recent heartbeat AND ollamaOk=true — a
  // heartbeat that arrived on time but reports Ollama unreachable
  // (HomeStatus.ollamaOk's doc comment: "the bridge process is up... but
  // Ollama itself isn't answering") means a Home-mode request would fail
  // anyway, so surfacing that as "online" would be misleading.
  const online = ageSeconds <= STALE_AFTER_SECONDS && item.ollamaOk;

  return json(200, {
    online,
    lastHeartbeatAt: item.lastHeartbeatAt,
    model: item.model,
    ollamaOk: item.ollamaOk,
  });
};

function json(statusCode: number, body: unknown): APIGatewayProxyResultV2 {
  return { statusCode, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) };
}

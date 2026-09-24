import { GetSecretValueCommand, SecretsManagerClient } from '@aws-sdk/client-secrets-manager';

const client = new SecretsManagerClient({});
const cache = new Map<string, string>();

/**
 * Fetches a Secrets Manager secret's plaintext string value (an ARN or a
 * name both work as `SecretId` — see AWS's GetSecretValue reference),
 * cached in module scope for the lifetime of this Lambda execution
 * environment: a fresh cache on every cold start, reused across warm
 * invocations. Fine for what this backend stores here (Telegram bot
 * tokens, the webhook secret) — none of it rotates mid-flight in a way
 * that would need a fresher read than "once per cold start," the same
 * assumption lambdas/sns-platform-app-provider's one-off local fetchSecret
 * already made for the Firebase service-account key. Centralized here
 * instead of duplicated a third time now that two Phase 3 Lambdas
 * (ai-orchestrator, telegram-webhook) both need it.
 */
export async function getSecretString(secretId: string): Promise<string> {
  const cached = cache.get(secretId);
  if (cached !== undefined) return cached;
  const result = await client.send(new GetSecretValueCommand({ SecretId: secretId }));
  if (!result.SecretString) throw new Error(`secret ${secretId} has no SecretString`);
  cache.set(secretId, result.SecretString);
  return result.SecretString;
}

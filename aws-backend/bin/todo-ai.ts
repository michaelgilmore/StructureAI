#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { DataStack } from '../infra/lib/data-stack';
import { AuthStack } from '../infra/lib/auth-stack';
import { NotificationsStack } from '../infra/lib/notifications-stack';
import { AiStack } from '../infra/lib/ai-stack';
import { TelegramStack } from '../infra/lib/telegram-stack';
import { ApiStack } from '../infra/lib/api-stack';

const app = new cdk.App();

const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'us-east-1',
};

const dataStack = new DataStack(app, 'TodoAi-Data', { env });
const authStack = new AuthStack(app, 'TodoAi-Auth', { env });

// ApiStack now depends on this too: /device/token (see api-stack.ts) is
// backed by notificationsStack.deviceTokenFn.
const notificationsStack = new NotificationsStack(app, 'TodoAi-Notifications', {
  env,
  table: dataStack.table,
});

// Phase 2 — /ai/request[/{reqId}] (see api-stack.ts) is backed by
// aiStack.aiOrchestratorFn, same pattern as notificationsStack above.
const aiStack = new AiStack(app, 'TodoAi-Ai', {
  env,
  table: dataStack.table,
});

// Phase 3 — /home/status and /telegram/webhook (see api-stack.ts) are
// backed by telegramStack's two Lambdas. Also reaches into aiStack to add
// Home-mode dispatch's env vars/secret grant to aiOrchestratorFn — see
// telegram-stack.ts's doc comment on why that grant lives here rather than
// in ai-stack.ts. Must be created after both dataStack/notificationsStack
// (needs their table/reminderTopic) and aiStack (needs aiOrchestratorFn).
const telegramStack = new TelegramStack(app, 'TodoAi-Telegram', {
  env,
  table: dataStack.table,
  reminderTopic: notificationsStack.reminderTopic,
  aiOrchestratorFn: aiStack.aiOrchestratorFn,
});

new ApiStack(app, 'TodoAi-Api', {
  env,
  table: dataStack.table,
  userPool: authStack.userPool,
  userPoolClient: authStack.userPoolClient,
  deviceTokenFn: notificationsStack.deviceTokenFn,
  aiOrchestratorFn: aiStack.aiOrchestratorFn,
  telegramWebhookFn: telegramStack.telegramWebhookFn,
  homeStatusFn: telegramStack.homeStatusFn,
});

import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as sns from 'aws-cdk-lib/aws-sns';
import type { Construct } from 'constructs';
import * as path from 'node:path';

export interface TelegramStackProps extends cdk.StackProps {
  table: dynamodb.Table;
  reminderTopic: sns.Topic;
  /** From ai-stack.ts — this stack grants it read access to the bot-token secret and adds the two Telegram env vars it needs for Home-mode dispatch, the same way notifications-stack.ts's deviceTokenFn is created there but wired into api-stack.ts elsewhere. */
  aiOrchestratorFn: NodejsFunction;
}

/**
 * The Home Bridge Bot's Telegram @username (no leading `@`) — whatever you
 * name that bot in BotFather. Not a secret (Telegram usernames are public
 * by design), so this lives here as plain source rather than in Secrets
 * Manager — same rationale as ai-stack.ts's hardcoded `modelId`.
 *
 * **Edit this before your first deploy of this stack.** See
 * ../../README.md's "Telegram bridge" section for the full BotFather setup
 * steps this username comes from.
 */
const HOME_BRIDGE_BOT_USERNAME = 'REPLACE_WITH_YOUR_HOME_BRIDGE_BOT_USERNAME';

/**
 * Phase 3 — the Telegram ⇄ Ollama bridge. See
 * ../../docs/architecture.md §04 and ../../lambdas/telegram-webhook/,
 * ../../lambdas/home-status/, ../../lambdas/ai-request-timeout/.
 *
 * Owns the two Telegram secrets (Cloud Relay Bot token, webhook secret —
 * **you create both by hand, outside CDK**, same reasoning as
 * notifications-stack.ts's `fcmServiceAccountSecret`: a bot token has no
 * business in a CDK app's synth output or in version control — see
 * ../../README.md's "Telegram bridge" section for the exact `aws
 * secretsmanager create-secret` commands) and the three Phase-3 Lambdas.
 * `ai-orchestrator` (ai-stack.ts) is the one other function that needs the
 * bot token — granted read access here, from the stack that owns the
 * secret, rather than duplicating the secret lookup in ai-stack.ts.
 */
export class TelegramStack extends cdk.Stack {
  public readonly telegramWebhookFn: NodejsFunction;
  public readonly homeStatusFn: NodejsFunction;

  constructor(scope: Construct, id: string, props: TelegramStackProps) {
    super(scope, id, props);

    const botTokenSecret = secretsmanager.Secret.fromSecretNameV2(this, 'TelegramBotTokenSecret', 'todo-ai/telegram-bot-token');
    const webhookSecret = secretsmanager.Secret.fromSecretNameV2(this, 'TelegramWebhookSecret', 'todo-ai/telegram-webhook-secret');

    // --- ai-orchestrator (ai-stack.ts) needs these to dispatch Home-mode requests ---
    botTokenSecret.grantRead(props.aiOrchestratorFn);
    props.aiOrchestratorFn.addEnvironment('TELEGRAM_BOT_TOKEN_SECRET_ARN', botTokenSecret.secretArn);
    props.aiOrchestratorFn.addEnvironment('HOME_BRIDGE_BOT_USERNAME', HOME_BRIDGE_BOT_USERNAME);

    // --- telegram-webhook: POST /telegram/webhook, no Cognito authorizer (wired in api-stack.ts) ---
    this.telegramWebhookFn = new NodejsFunction(this, 'TelegramWebhookFn', {
      functionName: 'todo-ai-telegram-webhook',
      entry: path.join(__dirname, '../../lambdas/telegram-webhook/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 256,
      // Same 29s ceiling rationale as ai-stack.ts's aiOrchestratorFn — under
      // API Gateway's 30s integration cap. The one slow step here (a retry
      // send back to the Home Bridge Bot) is a single Telegram API call,
      // not a Bedrock/Ollama round trip, so this has plenty of headroom.
      timeout: cdk.Duration.seconds(29),
      environment: {
        TABLE_NAME: props.table.tableName,
        TOPIC_ARN: props.reminderTopic.topicArn,
        TELEGRAM_BOT_TOKEN_SECRET_ARN: botTokenSecret.secretArn,
        TELEGRAM_WEBHOOK_SECRET_ARN: webhookSecret.secretArn,
        HOME_BRIDGE_BOT_USERNAME,
      },
      bundling: { minify: true, sourceMap: true },
    });
    props.table.grantReadWriteData(this.telegramWebhookFn);
    props.reminderTopic.grantPublish(this.telegramWebhookFn);
    botTokenSecret.grantRead(this.telegramWebhookFn);
    webhookSecret.grantRead(this.telegramWebhookFn);

    // --- home-status: GET /home/status, Cognito-authenticated (wired in api-stack.ts) ---
    this.homeStatusFn = new NodejsFunction(this, 'HomeStatusFn', {
      functionName: 'todo-ai-home-status',
      entry: path.join(__dirname, '../../lambdas/home-status/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 256,
      timeout: cdk.Duration.seconds(10),
      environment: { TABLE_NAME: props.table.tableName },
      bundling: { minify: true, sourceMap: true },
    });
    props.table.grantReadData(this.homeStatusFn); // read-only — see ../../docs/architecture.md §05's "least-privilege IAM role" note

    // --- ai-request-timeout: EventBridge-scheduled, no API route ---
    const timeoutFn = new NodejsFunction(this, 'AiRequestTimeoutFn', {
      functionName: 'todo-ai-ai-request-timeout',
      entry: path.join(__dirname, '../../lambdas/ai-request-timeout/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 256,
      timeout: cdk.Duration.seconds(30),
      environment: {
        TABLE_NAME: props.table.tableName,
        TOPIC_ARN: props.reminderTopic.topicArn,
      },
      bundling: { minify: true, sourceMap: true },
    });
    props.table.grantReadWriteData(timeoutFn); // read GSI1, conditionally update stuck items — see index.ts's failIfStillPending
    props.reminderTopic.grantPublish(timeoutFn);

    new events.Rule(this, 'AiRequestTimeoutSchedule', {
      ruleName: 'todo-ai-ai-request-timeout-schedule',
      schedule: events.Schedule.rate(cdk.Duration.minutes(1)),
      targets: [new targets.LambdaFunction(timeoutFn)],
    });

    new cdk.CfnOutput(this, 'HomeBridgeBotUsername', { value: HOME_BRIDGE_BOT_USERNAME });
  }
}

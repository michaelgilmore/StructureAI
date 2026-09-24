import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
import type { Construct } from 'constructs';
import * as path from 'node:path';

export interface AiStackProps extends cdk.StackProps {
  table: dynamodb.Table;
}

/**
 * Phase 2 — Bedrock-backed plan generation + dialog. See
 * ../../docs/architecture.md §03/§05/§07 and ../../lambdas/ai-orchestrator.
 *
 * This stack itself only ever wires up Bedrock (Cloud mode) — the Bedrock
 * IAM grants below apply regardless of which mode a given request uses,
 * since `aiOrchestratorFn` is one Lambda handling both. Home mode's own
 * infra (the bot-token secret grant, `TELEGRAM_BOT_TOKEN_SECRET_ARN`/
 * `HOME_BRIDGE_BOT_USERNAME` env vars) is added to `aiOrchestratorFn` from
 * telegram-stack.ts instead, once this stack's export of it exists — same
 * "own the resource, wire it into what needs it from there" pattern
 * notifications-stack.ts's `deviceTokenFn` already uses with api-stack.ts.
 */
export class AiStack extends cdk.Stack {
  public readonly aiOrchestratorFn: NodejsFunction;

  constructor(scope: Construct, id: string, props: AiStackProps) {
    super(scope, id, props);

    // Cross-region inference profile, not the bare foundation-model id —
    // several current Claude models on Bedrock reject on-demand
    // InvokeModel/Converse against the bare model id and require an
    // inference-profile id instead. "us." routes within US regions only
    // (no data leaves the US) — see lambdas/ai-orchestrator/bedrock.ts's
    // doc comment for the override env var and the Haiku alternative.
    const modelId = 'anthropic.claude-sonnet-4-5-20250929-v1:0';
    const inferenceProfileId = `us.${modelId}`;

    this.aiOrchestratorFn = new NodejsFunction(this, 'AiOrchestratorFn', {
      functionName: 'todo-ai-ai-orchestrator',
      entry: path.join(__dirname, '../../lambdas/ai-orchestrator/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 512,
      // API Gateway HTTP APIs cap an integration at 30s — Bedrock's own
      // reply is typically 1-4s (architecture.md §03), so this is headroom
      // for a slow model response, not the expected case. Keep it under
      // API Gateway's ceiling so a genuinely stuck call fails with a clear
      // Lambda timeout in CloudWatch Logs rather than an opaque 5xx from
      // the gateway cutting the connection first.
      timeout: cdk.Duration.seconds(29),
      environment: {
        TABLE_NAME: props.table.tableName,
        BEDROCK_MODEL_ID: inferenceProfileId,
      },
      bundling: { minify: true, sourceMap: true },
    });
    props.table.grantReadWriteData(this.aiOrchestratorFn);

    // Two-statement pattern is what AWS's own docs show for calling a
    // model through a cross-region inference profile: permission on the
    // inference-profile resource itself, PLUS permission on the underlying
    // foundation-model resource (which is what actually serves the
    // request, in whichever region the profile routes to) — scoped back
    // down via the InferenceProfileArn condition rather than left open to
    // any foundation model. Verified against
    // https://docs.aws.amazon.com/bedrock/latest/userguide/inference-profiles-prereq.html
    // before writing this (that page's example uses a `global.` profile in
    // one specific region; the foundation-model Resource here uses `*` for
    // region instead of enumerating exact CRIS destination regions, since
    // that destination list is AWS-managed and could change — the
    // Condition is what actually does the scoping, not the wildcard).
    const inferenceProfileArn = `arn:aws:bedrock:${this.region}:${this.account}:inference-profile/${inferenceProfileId}`;
    const bedrockActions = ['bedrock:InvokeModel', 'bedrock:InvokeModelWithResponseStream', 'bedrock:Converse', 'bedrock:ConverseStream'];

    this.aiOrchestratorFn.addToRolePolicy(new iam.PolicyStatement({
      actions: bedrockActions,
      resources: [inferenceProfileArn],
    }));
    this.aiOrchestratorFn.addToRolePolicy(new iam.PolicyStatement({
      actions: bedrockActions,
      resources: [`arn:aws:bedrock:*::foundation-model/${modelId}`],
      conditions: { StringLike: { 'bedrock:InferenceProfileArn': inferenceProfileArn } },
    }));

    // Anthropic's Claude models are distributed through Bedrock via AWS
    // Marketplace — invoking one (not just the one-time "request model
    // access" step in the console) requires the CALLING identity to also
    // hold aws-marketplace:ViewSubscriptions/Subscribe, or Bedrock's
    // "simplified model access" flow can't create the marketplace
    // agreement on first invoke and every Converse/InvokeModel call fails
    // with AccessDeniedException: "Model access is denied due to IAM user
    // or service role is not authorized to perform the required AWS
    // Marketplace actions (aws-marketplace:ViewSubscriptions,
    // aws-marketplace:Subscribe)". The bedrockActions grants above are
    // necessary but not sufficient — this is a separate permission
    // namespace, not a Bedrock resource ARN, hence its own unconditional
    // statement (marketplace subscription state isn't scoped to a Bedrock
    // ARN the way InvokeModel/Converse are above). Verified against
    // https://docs.aws.amazon.com/bedrock/latest/userguide/model-access-permissions.html
    // and confirmed as the fix for this exact error message via
    // https://github.com/Tracer-Cloud/opensre/issues/2067 and
    // https://repost.aws/knowledge-center/bedrock-resolve-marketplace-permission
    // before adding it — not a guess. `aws-marketplace:Unsubscribe` is
    // deliberately left out: the Lambda only ever needs to invoke, never
    // to tear down the account's subscription.
    this.aiOrchestratorFn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['aws-marketplace:ViewSubscriptions', 'aws-marketplace:Subscribe'],
      resources: ['*'],
    }));

    new cdk.CfnOutput(this, 'BedrockModelId', { value: inferenceProfileId });
  }
}

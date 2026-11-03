import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
// apigatewayv2 graduated out of the "-alpha" packages into aws-cdk-lib
// proper back in CDK v2.112.0 (the -integrations/-authorizers alpha
// packages followed shortly after, last published at 2.114.1-alpha.0 in
// Dec 2023) — well below the 2.150.0 pinned in package.json, so the stable
// import paths below are correct for this pin. The alpha packages are gone
// from the npm registry entirely, which is why `npm install` failed with
// ETARGET on @aws-cdk/aws-apigatewayv2-alpha@2.150.0-alpha.0.
import { HttpApi, CorsHttpMethod, HttpMethod } from 'aws-cdk-lib/aws-apigatewayv2';
import { HttpLambdaIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import { HttpUserPoolAuthorizer } from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import type { Construct } from 'constructs';
import * as path from 'node:path';

export interface ApiStackProps extends cdk.StackProps {
  table: dynamodb.Table;
  userPool: cognito.UserPool;
  userPoolClient: cognito.UserPoolClient;
  deviceTokenFn: NodejsFunction;
  aiOrchestratorFn: NodejsFunction;
  /** From telegram-stack.ts — Phase 3, see below. */
  telegramWebhookFn: NodejsFunction;
  homeStatusFn: NodejsFunction;
}

/**
 * The public API: an HTTP API (API Gateway v2) behind a Cognito JWT
 * authorizer, backed by the todo-api Lambda. See
 * ../../docs/architecture.md §05 and ../../docs/api/README.md for the
 * endpoint list — /ai/* (Phase 2) and /home/status (Phase 3) both sit
 * behind the same Cognito authorizer as /todos; /telegram/webhook
 * deliberately does not (see its route below).
 */
export class ApiStack extends cdk.Stack {
  public readonly httpApi: HttpApi;

  constructor(scope: Construct, id: string, props: ApiStackProps) {
    super(scope, id, props);

    const todoApiFn = new NodejsFunction(this, 'TodoApiFn', {
      functionName: 'todo-ai-todo-api',
      entry: path.join(__dirname, '../../lambdas/todo-api/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 256,
      timeout: cdk.Duration.seconds(10),
      environment: { TABLE_NAME: props.table.tableName },
      bundling: { minify: true, sourceMap: true },
    });
    props.table.grantReadWriteData(todoApiFn);

    // Goals CRUD — see ../../lambdas/goal-api/index.ts. Same shape as
    // todoApiFn above; a separate function rather than folded into it for
    // the same "one function per concern" reasoning that file's own doc
    // comment gives.
    const goalApiFn = new NodejsFunction(this, 'GoalApiFn', {
      functionName: 'todo-ai-goal-api',
      entry: path.join(__dirname, '../../lambdas/goal-api/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 256,
      timeout: cdk.Duration.seconds(10),
      environment: { TABLE_NAME: props.table.tableName },
      bundling: { minify: true, sourceMap: true },
    });
    props.table.grantReadWriteData(goalApiFn);

    // Routines CRUD — see ../../lambdas/routine-api/index.ts. Same shape
    // again, a separate function for the same reason goalApiFn is.
    const routineApiFn = new NodejsFunction(this, 'RoutineApiFn', {
      functionName: 'todo-ai-routine-api',
      entry: path.join(__dirname, '../../lambdas/routine-api/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 256,
      timeout: cdk.Duration.seconds(10),
      environment: { TABLE_NAME: props.table.tableName },
      bundling: { minify: true, sourceMap: true },
    });
    props.table.grantReadWriteData(routineApiFn);

    const authorizer = new HttpUserPoolAuthorizer('Authorizer', props.userPool, {
      userPoolClients: [props.userPoolClient],
      identitySource: ['$request.header.Authorization'],
    });

    this.httpApi = new HttpApi(this, 'HttpApi', {
      apiName: 'todo-ai-api',
      corsPreflight: {
        allowOrigins: ['*'],
        allowMethods: [CorsHttpMethod.GET, CorsHttpMethod.POST, CorsHttpMethod.PATCH, CorsHttpMethod.DELETE],
        allowHeaders: ['Authorization', 'Content-Type'],
      },
    });

    const todoIntegration = new HttpLambdaIntegration('TodoApiIntegration', todoApiFn);

    const todoRoutes: [HttpMethod, string][] = [
      [HttpMethod.GET, '/todos'],
      [HttpMethod.POST, '/todos'],
      [HttpMethod.GET, '/todos/{id}'],
      [HttpMethod.PATCH, '/todos/{id}'],
      [HttpMethod.DELETE, '/todos/{id}'],
    ];
    for (const [method, routePath] of todoRoutes) {
      this.httpApi.addRoutes({
        path: routePath,
        methods: [method],
        integration: todoIntegration,
        authorizer,
      });
    }

    // ai-orchestrator reads these during a 'planning' conversation (see
    // ../../lambdas/ai-orchestrator/index.ts's buildSubjectContext) —
    // this route is how the app itself manages them, same pattern as
    // /todos above.
    const goalIntegration = new HttpLambdaIntegration('GoalApiIntegration', goalApiFn);
    const goalRoutes: [HttpMethod, string][] = [
      [HttpMethod.GET, '/goals'],
      [HttpMethod.POST, '/goals'],
      [HttpMethod.GET, '/goals/{id}'],
      [HttpMethod.PATCH, '/goals/{id}'],
      [HttpMethod.DELETE, '/goals/{id}'],
    ];
    for (const [method, routePath] of goalRoutes) {
      this.httpApi.addRoutes({
        path: routePath,
        methods: [method],
        integration: goalIntegration,
        authorizer,
      });
    }

    // ai-orchestrator also reads these during a 'planning' conversation,
    // alongside goals — see buildSubjectContext's 'planning' case.
    const routineIntegration = new HttpLambdaIntegration('RoutineApiIntegration', routineApiFn);
    const routineRoutes: [HttpMethod, string][] = [
      [HttpMethod.GET, '/routines'],
      [HttpMethod.POST, '/routines'],
      [HttpMethod.GET, '/routines/{id}'],
      [HttpMethod.PATCH, '/routines/{id}'],
      [HttpMethod.DELETE, '/routines/{id}'],
    ];
    for (const [method, routePath] of routineRoutes) {
      this.httpApi.addRoutes({
        path: routePath,
        methods: [method],
        integration: routineIntegration,
        authorizer,
      });
    }

    // Closes the push-notification gap flagged in android-app/README.md —
    // see ../../lambdas/device-token/README.md and notifications-stack.ts,
    // which is where props.deviceTokenFn actually comes from. Same
    // Cognito-authorizer pattern as the /todos routes above.
    const deviceTokenIntegration = new HttpLambdaIntegration('DeviceTokenIntegration', props.deviceTokenFn);
    this.httpApi.addRoutes({
      path: '/device/token',
      methods: [HttpMethod.POST],
      integration: deviceTokenIntegration,
      authorizer,
    });

    // Phase 2 — see ../../lambdas/ai-orchestrator/index.ts and
    // ../../docs/api/README.md for the request/response contract.
    // props.aiOrchestratorFn comes from ai-stack.ts, same pattern as
    // deviceTokenFn above.
    const aiIntegration = new HttpLambdaIntegration('AiOrchestratorIntegration', props.aiOrchestratorFn);
    const aiRoutes: [HttpMethod, string][] = [
      [HttpMethod.POST, '/ai/request'],
      [HttpMethod.GET, '/ai/request/{reqId}'],
    ];
    for (const [method, routePath] of aiRoutes) {
      this.httpApi.addRoutes({
        path: routePath,
        methods: [method],
        integration: aiIntegration,
        authorizer,
      });
    }

    // Phase 3 — see ../../lambdas/home-status/index.ts. Same Cognito
    // authorizer as everything else the app itself calls.
    this.httpApi.addRoutes({
      path: '/home/status',
      methods: [HttpMethod.GET],
      integration: new HttpLambdaIntegration('HomeStatusIntegration', props.homeStatusFn),
      authorizer,
    });

    // Phase 3 — see ../../lambdas/telegram-webhook/index.ts. Deliberately
    // NO authorizer: Telegram calls this, not a signed-in app user, and
    // Telegram can't present a Cognito JWT. Authenticity is instead
    // verified inside the Lambda itself via the
    // X-Telegram-Bot-Api-Secret-Token header (see that file's doc comment)
    // — the same reason this route needs no usage-plan/authorizer
    // exception elsewhere, just a handler that checks its own secret.
    this.httpApi.addRoutes({
      path: '/telegram/webhook',
      methods: [HttpMethod.POST],
      integration: new HttpLambdaIntegration('TelegramWebhookIntegration', props.telegramWebhookFn),
    });

    new cdk.CfnOutput(this, 'ApiUrl', { value: this.httpApi.apiEndpoint });
  }
}

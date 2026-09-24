import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
import { Provider } from 'aws-cdk-lib/custom-resources';
import type { Construct } from 'constructs';
import * as path from 'node:path';

export interface NotificationsStackProps extends cdk.StackProps {
  table: dynamodb.Table;
}

/**
 * See ../../docs/architecture.md §05. `reminder-scan` runs on a schedule
 * (EventBridge rate rule), queries the sparse GSI1=REMINDER index, and
 * publishes due items to `reminderTopic`. `deviceTokenFn` (wired to
 * `POST /device/token` in api-stack.ts) is what actually gets something
 * subscribed to that topic — see its own doc comment below and
 * ../../lambdas/device-token/README.md.
 */
export class NotificationsStack extends cdk.Stack {
  public readonly reminderTopic: sns.Topic;
  public readonly deviceTokenFn: NodejsFunction;

  constructor(scope: Construct, id: string, props: NotificationsStackProps) {
    super(scope, id, props);

    this.reminderTopic = new sns.Topic(this, 'ReminderTopic', {
      topicName: 'todo-ai-reminders',
      displayName: 'Structure reminders',
    });

    const reminderScanFn = new NodejsFunction(this, 'ReminderScanFn', {
      functionName: 'todo-ai-reminder-scan',
      entry: path.join(__dirname, '../../lambdas/reminder-scan/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 256,
      timeout: cdk.Duration.seconds(30),
      environment: {
        TABLE_NAME: props.table.tableName,
        TOPIC_ARN: this.reminderTopic.topicArn,
      },
      bundling: { minify: true, sourceMap: true },
    });

    props.table.grantReadWriteData(reminderScanFn); // read GSI1, clear it after firing
    this.reminderTopic.grantPublish(reminderScanFn);

    new events.Rule(this, 'ReminderScanSchedule', {
      ruleName: 'todo-ai-reminder-scan-schedule',
      schedule: events.Schedule.rate(cdk.Duration.minutes(5)),
      targets: [new targets.LambdaFunction(reminderScanFn)],
    });

    // --- Push notifications: SNS platform application + device-token Lambda ---
    //
    // This secret is NOT created by CDK — it holds a Firebase service
    // account private key, which has no business living in a CDK app's
    // synth output or in version control. You create it once, by hand,
    // before this stack will deploy — see ../../README.md, "Push
    // notification setup". `fromSecretNameV2` just looks up a secret
    // that's expected to already exist; if it doesn't, this stack's
    // deploy fails with a clear "secret not found" error, not a mysterious
    // one.
    const fcmServiceAccountSecret = secretsmanager.Secret.fromSecretNameV2(
      this,
      'FcmServiceAccountSecret',
      'todo-ai/fcm-service-account',
    );

    // AWS's API still calls this platform "GCM" even though the credential
    // is a Firebase (FCM) *v1* service-account key, not the old
    // GCM/legacy-FCM server key — see
    // https://docs.aws.amazon.com/sns/latest/dg/sns-fcm-authentication-methods.html.
    // No PlatformPrincipal is needed for GCM either way (key- or
    // token-based).
    //
    // This can't be a normal CDK construct (`sns.CfnPlatformApplication`
    // does not exist — CloudFormation has no native
    // `AWS::SNS::PlatformApplication` resource type at all, a real,
    // still-open CDK gap: https://github.com/aws/aws-cdk/issues/36681, not
    // something CDK forgot to wrap). ../../lambdas/sns-platform-app-provider/
    // is a small hand-written custom-resource Lambda that does the
    // Create/Update/Delete SNS calls itself — see that file's doc comment
    // for why this needed to be a real Lambda (fetching the secret itself
    // at runtime) rather than the simpler generic `AwsCustomResource`
    // helper (which would have meant putting the unwrapped secret value
    // into the Custom Resource's own properties — exactly what Secrets
    // Manager is here to avoid).
    const platformAppProviderFn = new NodejsFunction(this, 'SnsPlatformAppProviderFn', {
      functionName: 'todo-ai-sns-platform-app-provider',
      entry: path.join(__dirname, '../../lambdas/sns-platform-app-provider/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 256,
      timeout: cdk.Duration.seconds(30),
      bundling: { minify: true, sourceMap: true },
    });
    fcmServiceAccountSecret.grantRead(platformAppProviderFn);
    platformAppProviderFn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['sns:CreatePlatformApplication', 'sns:SetPlatformApplicationAttributes', 'sns:DeletePlatformApplication'],
      resources: ['*'], // same rationale as deviceTokenFn's policy below — no predictable ARN before creation
    }));

    const platformAppProvider = new Provider(this, 'SnsPlatformAppProvider', {
      onEventHandler: platformAppProviderFn,
    });

    const fcmPlatformApp = new cdk.CustomResource(this, 'FcmPlatformApplication', {
      serviceToken: platformAppProvider.serviceToken,
      properties: {
        Name: 'todo-ai-fcm',
        SecretArn: fcmServiceAccountSecret.secretArn,
      },
    });
    const fcmPlatformAppArn = fcmPlatformApp.getAttString('PlatformApplicationArn');

    this.deviceTokenFn = new NodejsFunction(this, 'DeviceTokenFn', {
      functionName: 'todo-ai-device-token',
      entry: path.join(__dirname, '../../lambdas/device-token/index.ts'),
      runtime: lambda.Runtime.NODEJS_20_X,
      memorySize: 256,
      timeout: cdk.Duration.seconds(10),
      environment: {
        TABLE_NAME: props.table.tableName,
        TOPIC_ARN: this.reminderTopic.topicArn,
        PLATFORM_APPLICATION_ARN: fcmPlatformAppArn,
      },
      bundling: { minify: true, sourceMap: true },
    });
    props.table.grantReadWriteData(this.deviceTokenFn);
    // No CDK grant helpers for platform-endpoint management — these are
    // narrow, resource-scoped IAM actions rather than a broad managed
    // policy.
    this.deviceTokenFn.addToRolePolicy(new iam.PolicyStatement({
      actions: [
        'sns:CreatePlatformEndpoint',
        'sns:DeleteEndpoint',
        'sns:Subscribe',
        'sns:Unsubscribe',
      ],
      resources: ['*'], // platform endpoints don't have a predictable ARN before creation; scoping further would need a wildcard on the platform app's ARN prefix, which SNS's IAM condition support doesn't cleanly express here
    }));

    new cdk.CfnOutput(this, 'ReminderTopicArn', { value: this.reminderTopic.topicArn });
    new cdk.CfnOutput(this, 'FcmPlatformApplicationArn', { value: fcmPlatformAppArn });
  }
}

import type { CloudFormationCustomResourceEvent } from 'aws-lambda';
import {
  CreatePlatformApplicationCommand,
  DeletePlatformApplicationCommand,
  SetPlatformApplicationAttributesCommand,
  SNSClient,
} from '@aws-sdk/client-sns';
import { GetSecretValueCommand, SecretsManagerClient } from '@aws-sdk/client-secrets-manager';

const sns = new SNSClient({});
const secretsManager = new SecretsManagerClient({});

interface OnEventResponse {
  PhysicalResourceId: string;
  Data?: Record<string, string>;
}

/**
 * CloudFormation custom-resource handler (the Provider framework's
 * "onEvent" Lambda — see ../../infra/lib/notifications-stack.ts, which
 * wraps this in a `custom_resources.Provider`) backing the SNS platform
 * application that stack needs for FCM push.
 *
 * Why this exists at all instead of a normal CDK construct: CloudFormation
 * has no native `AWS::SNS::PlatformApplication` resource type — a real,
 * still-open gap (https://github.com/aws/aws-cdk/issues/36681), not
 * something CDK simply forgot to wrap. A generic `AwsCustomResource`
 * (aws-cdk-lib/custom-resources' one-SDK-call-per-lifecycle-event helper)
 * would have been much less code, but it has no way to fetch a secret and
 * use the result in the same call — the credential would have to be passed
 * in as a literal Custom Resource property instead. Secrets Manager's
 * `{{resolve:secretsmanager:...}}` dynamic-reference resolution only
 * applies to a fixed allowlist of NATIVE resource properties, not to
 * arbitrary custom-resource parameters, so that would have put the
 * Firebase service-account private key in plaintext into the Custom
 * Resource's request payload (and CloudFormation's event history) —
 * exactly what ../../docs/architecture.md §09's "secrets ... never in the
 * app or in source" is meant to prevent. This handler instead receives
 * only the secret's ARN as a resource property and fetches the value
 * itself, at runtime, the same way lambdas/device-token and
 * lambdas/ai-orchestrator fetch their own secrets/config.
 */
export const handler = async (event: CloudFormationCustomResourceEvent): Promise<OnEventResponse> => {
  const props = event.ResourceProperties as unknown as { Name: string; SecretArn: string };

  switch (event.RequestType) {
    case 'Create':
    case 'Update': {
      const credential = await fetchSecret(props.SecretArn);
      const created = await sns.send(new CreatePlatformApplicationCommand({
        Name: props.Name,
        Platform: 'GCM',
        Attributes: { PlatformCredential: credential },
      }));
      const arn = created.PlatformApplicationArn;
      if (!arn) throw new Error('SNS did not return a PlatformApplicationArn');

      if (event.RequestType === 'Update') {
        // CreatePlatformApplication reuses the existing application for the
        // same Name rather than erroring (matching CreatePlatformEndpoint's
        // documented reuse behavior — see lambdas/device-token/index.ts's
        // comment on that), but it doesn't refresh Attributes on an
        // existing app the way Create does on first creation. Set them
        // explicitly here so a rotated Firebase key actually takes effect
        // on a stack update, not just on first deploy.
        await sns.send(new SetPlatformApplicationAttributesCommand({
          PlatformApplicationArn: arn,
          Attributes: { PlatformCredential: credential },
        }));
      }

      return { PhysicalResourceId: arn, Data: { PlatformApplicationArn: arn } };
    }
    case 'Delete': {
      await sns.send(new DeletePlatformApplicationCommand({ PlatformApplicationArn: event.PhysicalResourceId }));
      return { PhysicalResourceId: event.PhysicalResourceId };
    }
  }
};

async function fetchSecret(secretArn: string): Promise<string> {
  const result = await secretsManager.send(new GetSecretValueCommand({ SecretId: secretArn }));
  if (!result.SecretString) throw new Error(`secret ${secretArn} has no SecretString`);
  return result.SecretString;
}

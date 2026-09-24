import * as cdk from 'aws-cdk-lib';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import type { Construct } from 'constructs';

/**
 * Cognito for a single-user app. Self-sign-up is deliberately off — see
 * ../../docs/architecture.md §09 ("Auth via Cognito even for a single-user
 * app"). After deploying, create the one account manually. The username
 * can be anything you like — email is still required as a separate
 * attribute (and works as a second way to sign in), it just isn't forced
 * to double as the username:
 *
 *   aws cognito-idp admin-create-user \
 *     --user-pool-id <UserPoolId output> \
 *     --username mike \
 *     --user-attributes Name=email,Value=you@example.com Name=email_verified,Value=true
 */
export class AuthStack extends cdk.Stack {
  public readonly userPool: cognito.UserPool;
  public readonly userPoolClient: cognito.UserPoolClient;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // Logical ID 'UserPoolV2', not 'UserPool': switching signInAliases
    // changes an immutable Cognito property (AliasAttributes/
    // UsernameAttributes), and CloudFormation's Cognito::UserPool resource
    // doesn't reliably turn that into an automatic replacement — it tries
    // an in-place update and Cognito's API rejects it. Giving the
    // construct a new logical ID makes CloudFormation treat it as a
    // brand-new resource: it creates a fresh pool and, because
    // removalPolicy is RETAIN, orphans the old one instead of deleting it
    // (find it later in the Cognito console/CLI if you want to clean it
    // up by hand). This does mean a new UserPoolId/UserPoolClientId — redo
    // the admin-create-user step against the new pool afterwards.
    this.userPool = new cognito.UserPool(this, 'UserPoolV2', {
      userPoolName: 'todo-ai-users',
      selfSignUpEnabled: false,
      // username: true keeps the username field free-form (rather than
      // forcing it to literally be your email address); email: true keeps
      // email available as a second thing you can sign in with.
      signInAliases: { username: true, email: true },
      autoVerify: { email: true },
      standardAttributes: { email: { required: true, mutable: false } },
      passwordPolicy: {
        minLength: 12,
        requireLowercase: true,
        requireUppercase: true,
        requireDigits: true,
        requireSymbols: true,
      },
      accountRecovery: cognito.AccountRecovery.EMAIL_ONLY,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // Public client (no secret) — the Android app authenticates directly
    // with SRP via the Cognito SDK, not a hosted-UI redirect.
    //
    // adminUserPassword is also on so you can get a test JWT straight from
    // the AWS CLI (`admin-initiate-auth` with USERNAME/PASSWORD, no SRP
    // math) — see ../../TESTING.md. It only works for someone holding
    // AWS credentials on this account, not from the internet, so it's a
    // reasonable thing to leave on for a single-user hobby project; turn
    // it off (drop `adminUserPassword: true`) if that stops being true.
    this.userPoolClient = this.userPool.addClient('AndroidClient', {
      authFlows: { userSrp: true, adminUserPassword: true },
      generateSecret: false,
      accessTokenValidity: cdk.Duration.hours(1),
      idTokenValidity: cdk.Duration.hours(1),
      refreshTokenValidity: cdk.Duration.days(30),
    });

    new cdk.CfnOutput(this, 'UserPoolId', { value: this.userPool.userPoolId });
    new cdk.CfnOutput(this, 'UserPoolClientId', { value: this.userPoolClient.userPoolClientId });
  }
}

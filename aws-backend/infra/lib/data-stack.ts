import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import type { Construct } from 'constructs';

/**
 * The single DynamoDB table described in ../../docs/architecture.md §05.
 * One table, PK/SK per item type, plus a sparse GSI1 used today for the
 * reminder scan (GSI1PK="REMINDER") and reserved for Plan/Conversation/
 * PendingAiRequest/HomeStatus lookups in later phases.
 */
export class DataStack extends cdk.Stack {
  public readonly table: dynamodb.Table;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    this.table = new dynamodb.Table(this, 'Table', {
      tableName: 'TodoAi',
      partitionKey: { name: 'PK', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'SK', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      // `pointInTimeRecoverySpecification` (the newer, non-deprecated form)
      // isn't on TableProps yet at the 2.150.0 pinned in package.json — it
      // was added in a later aws-cdk-lib release. Using the older boolean
      // prop here on purpose so this matches the pinned version; if you
      // bump aws-cdk-lib later, `pointInTimeRecoverySpecification: {
      // pointInTimeRecoveryEnabled: true }` is the non-deprecated form.
      pointInTimeRecovery: true,
      // RETAIN: this is real personal data. Losing the table on a botched
      // `cdk destroy` should not be possible by accident. Switch to DESTROY
      // only in a throwaway/dev-account deployment.
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      // Phase 2 (AI): PendingAiRequest items self-expire (see
      // shared/types.ts's doc comment and lambdas/ai-orchestrator) — DynamoDB
      // only expires items that actually carry this attribute, so Todo/Plan/
      // Conversation/HomeStatus items (which never set `ttl`) are unaffected.
      timeToLiveAttribute: 'ttl',
    });

    this.table.addGlobalSecondaryIndex({
      indexName: 'GSI1',
      partitionKey: { name: 'GSI1PK', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'GSI1SK', type: dynamodb.AttributeType.STRING },
      projectionType: dynamodb.ProjectionType.ALL,
    });

    new cdk.CfnOutput(this, 'TableName', { value: this.table.tableName });
  }
}

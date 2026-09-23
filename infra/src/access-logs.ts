import * as cdk from "aws-cdk-lib";
import { aws_logs as logs } from "aws-cdk-lib";

/** CloudFront's v2 delivery lives in us-east-1, apart from the serving distribution. */
export function createAccessLogsStack(app: cdk.App, account: string): cdk.Stack {
  const stack = new cdk.Stack(app, "NullnullStgCloudFrontAccessLogs", {
    env: { account, region: "us-east-1" },
  });
  for (const [key, value] of Object.entries({
    Project: "Nullnull",
    Environment: "staging",
    ManagedBy: "CDK",
    Expiry: "2026-10-25",
  })) cdk.Tags.of(stack).add(key, value);

  // The existing distribution is deliberately not updated. This stack only attaches a v2 log
  // delivery, so enabling access logs cannot close the API gate or replace an origin.
  const distributionId = new cdk.CfnParameter(stack, "DistributionId", {
    type: "String",
    allowedPattern: "^[A-Z0-9]{10,20}$",
  });
  const groupName = "NullnullStgCloudFrontAccessLogs";
  const group = new logs.LogGroup(stack, "AccessLogs", {
    logGroupName: groupName,
    retention: logs.RetentionDays.ONE_MONTH,
    removalPolicy: cdk.RemovalPolicy.RETAIN,
  });
  const source = new logs.CfnDeliverySource(stack, "CloudFrontSource", {
    name: "nullnull-stg-cf-access-source",
    resourceArn: cdk.Fn.sub(
      "arn:aws:cloudfront::${AWS::AccountId}:distribution/${DistributionId}",
    ),
    logType: "ACCESS_LOGS",
  });
  const destination = new logs.CfnDeliveryDestination(stack, "CloudWatchDestination", {
    name: "nullnull-stg-cf-access-destination",
    destinationResourceArn: stack.formatArn({
      service: "logs",
      resource: "log-group",
      resourceName: groupName,
      arnFormat: cdk.ArnFormat.COLON_RESOURCE_NAME,
    }),
    outputFormat: "json",
  });
  destination.addResourceDependency(group.node.defaultChild as cdk.CfnResource);

  const delivery = new logs.CfnDelivery(stack, "AccessDelivery", {
    deliverySourceName: source.name,
    deliveryDestinationArn: destination.attrArn,
    // Selected fields answer when, where, what route, how it responded, and a cautious IP estimate.
    // Query, Cookie, Referer and forwarded IP may contain tokens or user text and are excluded.
    recordFields: [
      "date", "time", "x-edge-location", "c-ip", "c-country", "asn",
      "cs-method", "cs(Host)", "cs-uri-stem", "sc-status", "sc-bytes",
      "cs(User-Agent)", "x-edge-result-type", "x-edge-detailed-result-type",
      "x-edge-request-id", "time-taken", "cache-behavior-path-pattern",
    ],
  });
  delivery.addResourceDependency(source);

  new cdk.CfnOutput(stack, "LogGroupName", { value: group.logGroupName });
  new cdk.CfnOutput(stack, "DeliveryId", { value: delivery.attrDeliveryId });
  return stack;
}

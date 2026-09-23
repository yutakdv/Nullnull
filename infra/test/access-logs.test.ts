import { test } from "node:test";
import assert from "node:assert/strict";
import * as cdk from "aws-cdk-lib";
import { Template } from "aws-cdk-lib/assertions";
import { createAccessLogsStack } from "../src/access-logs";

test("CloudFront v2 logs are private, retained 30 days and omit request secrets", () => {
  const app = new cdk.App();
  const stack = createAccessLogsStack(app, "1".repeat(12));
  const template = Template.fromStack(stack);
  template.resourceCountIs("AWS::CloudFront::Distribution", 0);
  template.resourceCountIs("AWS::Logs::LogGroup", 1);
  template.resourceCountIs("AWS::Logs::DeliverySource", 1);
  template.resourceCountIs("AWS::Logs::DeliveryDestination", 1);
  template.resourceCountIs("AWS::Logs::Delivery", 1);
  template.hasResourceProperties("AWS::Logs::LogGroup", {
    LogGroupName: "NullnullStgCloudFrontAccessLogs",
    RetentionInDays: 30,
  });
  const source = Object.values(template.findResources("AWS::Logs::DeliverySource"))[0] as any;
  assert.equal(source.Properties.LogType, "ACCESS_LOGS");
  assert.deepEqual(source.Properties.ResourceArn, {
    "Fn::Sub": "arn:aws:cloudfront::${AWS::AccountId}:distribution/${DistributionId}",
  });
  const destination = Object.values(template.findResources("AWS::Logs::DeliveryDestination"))[0] as any;
  assert.equal(destination.Properties.OutputFormat, "json");
  const delivery = Object.values(template.findResources("AWS::Logs::Delivery"))[0] as any;
  const fields = delivery.Properties.RecordFields as string[];
  for (const required of ["date", "time", "c-ip", "cs-uri-stem", "sc-status", "c-country", "asn"]) {
    assert(fields.includes(required), `missing analysis field ${required}`);
  }
  for (const sensitive of ["cs-uri-query", "cs(Cookie)", "cs(Referer)", "x-forwarded-for"]) {
    assert(!fields.includes(sensitive), `sensitive field ${sensitive} must not be logged`);
  }
});

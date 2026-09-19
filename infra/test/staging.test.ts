import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import * as cdk from "aws-cdk-lib";
import { Template, Match } from "aws-cdk-lib/assertions";
import { createStacks } from "../src/staging";
const directory = mkdtempSync(join(tmpdir(), "nullnull-infra-"));
mkdirSync(join(directory, "web"));
writeFileSync(
  join(directory, "web", "index.html"),
  "<!doctype html><title>synthetic</title>",
);
const app = new cdk.App({ outdir: join(directory, "assembly") });
const stacks = createStacks(app, {
  account: "1".repeat(12),
  release: {
    releaseVersion: "v0.1.0-rc.1",
    apiImageDigest: "sha256:" + "a".repeat(64),
    aiImageDigest: "sha256:" + "b".repeat(64),
    aiCatalogVersion: "KTO_KOR_SERVICE_2:4",
  },
  webDirectory: join(directory, "web"),
});
const templates = Object.fromEntries(
  Object.entries(stacks).map(([k, s]) => [k, Template.fromStack(s)]),
);
test("private encrypted Multi-AZ DB retains snapshots and backups", () => {
  templates.data.hasResourceProperties("AWS::RDS::DBInstance", {
    PubliclyAccessible: false,
    MultiAZ: true,
    StorageEncrypted: true,
    DeletionProtection: true,
    BackupRetentionPeriod: 14,
  });
  templates.data.hasResource("AWS::RDS::DBInstance", {
    DeletionPolicy: "Snapshot",
    UpdateReplacePolicy: "Snapshot",
  });
});
test("no NAT or unrestricted ingress on public tasks", () => {
  templates.network.resourceCountIs("AWS::EC2::NatGateway", 0);
  // Inline rules and standalone AWS::EC2::SecurityGroupIngress resources are both ingress.
  for (const rule of ingressRules(templates.network)) {
    assert.notEqual(rule.CidrIp, "0.0.0.0/0");
    assert.notEqual(rule.CidrIpv6, "::/0");
  }
});
test("API and AI use immutable images, Flyway is disabled in service", () => {
  const tasks = Object.values(
    templates.services.findResources("AWS::ECS::TaskDefinition"),
  ) as any[];
  assert.equal(tasks.length, 2);
  for (const t of tasks)
    assert.match(
      JSON.stringify(t.Properties.ContainerDefinitions[0].Image),
      /@sha256:/,
    );
  const api = tasks.find(
    (t) => t.Properties.ContainerDefinitions[0].Name === "api",
  );
  assert(
    api.Properties.ContainerDefinitions[0].Environment.some(
      (e: any) => e.Name === "SPRING_FLYWAY_ENABLED" && e.Value === "false",
    ),
  );
  templates.services.hasResourceProperties("AWS::ECS::Service", {
    DeploymentConfiguration: Match.objectLike({
      DeploymentCircuitBreaker: { Enable: true, Rollback: true },
      MinimumHealthyPercent: 100,
      MaximumPercent: 200,
    }),
  });
});
test("migration uses distinct task and no web/jobs", () => {
  templates.migration.hasResourceProperties("AWS::ECS::TaskDefinition", {
    ContainerDefinitions: Match.arrayWith([
      Match.objectLike({
        Name: "migration",
        Command: ["--nullnull.migration-only=true"],
        Environment: Match.arrayWith([
          { Name: "NULLNULL_JOBS_ENABLED", Value: "false" },
        ]),
      }),
    ]),
  });
});
test("private edge with API cache disabled and closed default gate", () => {
  templates.platform.hasResourceProperties(
    "AWS::ElasticLoadBalancingV2::LoadBalancer",
    { Scheme: "internal" },
  );
  templates.web.hasResourceProperties("AWS::S3::Bucket", {
    PublicAccessBlockConfiguration: {
      BlockPublicAcls: true,
      BlockPublicPolicy: true,
      IgnorePublicAcls: true,
      RestrictPublicBuckets: true,
    },
  });
  templates.web.hasResourceProperties("AWS::CloudFront::Distribution", {
    DistributionConfig: Match.objectLike({
      CacheBehaviors: Match.arrayWith([
        Match.objectLike({
          PathPattern: "/api/*",
          CachePolicyId: cfDisabled(),
          AllowedMethods: Match.arrayWith(["PATCH", "POST", "DELETE"]),
        }),
      ]),
    }),
  });
  assert.equal(
    templates.web.toJSON().Parameters.TrafficEnabled.Default,
    "false",
  );
  // AWS managed SecurityHeadersPolicy on the web behavior.
  templates.web.hasResourceProperties("AWS::CloudFront::Distribution", {
    DistributionConfig: Match.objectLike({
      DefaultCacheBehavior: Match.objectLike({ ResponseHeadersPolicyId: "67f7725c-6f97-4210-82d7-5512b31e9d03" }),
    }),
  });
});
function ingressRules(t: Template): any[] {
  return [
    ...(Object.values(t.findResources("AWS::EC2::SecurityGroup")) as any[])
      .flatMap((r) => r.Properties.SecurityGroupIngress ?? []),
    ...(Object.values(t.findResources("AWS::EC2::SecurityGroupIngress")) as any[])
      .map((r) => r.Properties),
  ];
}
function cfDisabled() {
  return "4135ea2d-6df8-44a3-9df3-4b5a84be39ad";
}
test("OIDC exact subject and WAF has no sampled user requests", () => {
  // Literal values on purpose: re-reading github-oidc.json here would make the test agree with any edit.
  const prefix = "repo:yutakdv@98016178/Nullnull@1348534580:environment:";
  const trust = (name: string) =>
    (Object.values(templates.foundation.findResources("AWS::IAM::Role")) as any[])
      .find((r) => r.Properties.RoleName === name).Properties.AssumeRolePolicyDocument;
  for (const [name, sub] of [
    ["nullnull-stg-github-deploy", [prefix + "staging", prefix + "staging-infra"]],
    ["nullnull-stg-github-publish", prefix + "staging-build"],
  ] as const) {
    const doc = trust(name);
    assert.equal(doc.Statement.length, 1);
    assert.equal(doc.Statement[0].Action, "sts:AssumeRoleWithWebIdentity");
    assert.deepEqual(doc.Statement[0].Condition, {
      StringEquals: {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": sub,
      },
    });
  }
  templates.globalWaf.hasResourceProperties("AWS::WAFv2::WebACL", {
    Scope: "CLOUDFRONT",
    VisibilityConfig: Match.objectLike({ SampledRequestsEnabled: false }),
  });
});
test("GitHub roles are least privilege: no wildcard action, scoped run/pass/push", () => {
  const policies = Object.values(templates.foundation.findResources("AWS::IAM::Policy")) as any[];
  const statements = policies.flatMap((p) => p.Properties.PolicyDocument.Statement);
  const actions = statements.flatMap((s) => [s.Action].flat());
  assert(!actions.some((a: string) => a === "*" || a.endsWith(":*")), "no service-wide wildcard");
  for (const forbidden of ["ecr:BatchDeleteImage", "ecr:PutImageTagMutability", "iam:CreateRole",
    "secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue", "cloudformation:CreateStack"])
    assert(!actions.includes(forbidden), forbidden);
  const pass = statements.find((s) => [s.Action].flat().includes("iam:PassRole"));
  assert.deepEqual(pass.Condition, { StringEquals: { "iam:PassedToService": "ecs-tasks.amazonaws.com" } });
  assert.match(JSON.stringify(pass.Resource), /role\/NullnullStgMigration-MigrationTask\*/);
  const run = statements.find((s) => [s.Action].flat().includes("ecs:RunTask"));
  assert.match(JSON.stringify(run.Resource), /task-definition\/nullnull-stg-migration:\*/);
  assert(run.Condition.ArnEquals["ecs:cluster"]);
  // The CDK lookup role carries ReadOnlyAccess over the whole account: never reachable from GitHub.
  const assume = statements.filter((s) => [s.Action].flat().includes("sts:AssumeRole"));
  const kinds = assume.flatMap((s) => [s.Resource].flat()).map((r: string) =>
    /:role\/cdk-nnstg-([a-z-]+)-role-/.exec(r)?.[1]);
  assert.deepEqual([...new Set(kinds)].sort(), ["deploy", "file-publishing"]);
});
test("non-root containers on a read-only root get a writable /tmp before they start", () => {
  // Fargate mounts an empty bind volume as root:root 0755; the API image runs as uid 999.
  for (const [template, family, app] of [
    [templates.services, "nullnull-stg-api", "api"],
    [templates.migration, "nullnull-stg-ops", "ops"],
  ] as const) {
    const task = Object.values(template.findResources("AWS::ECS::TaskDefinition")).find(
      (r: any) => r.Properties.Family === family,
    ) as any;
    const containers = task.Properties.ContainerDefinitions;
    const init = containers.find((c: any) => c.Name === "tmp-permissions");
    assert.equal(init.Essential, false, family);
    assert.equal(init.User, "0");
    assert.equal(init.ReadonlyRootFilesystem, true);
    assert.deepEqual([init.EntryPoint, init.Command], [["sh", "-c"], ["chmod 1777 /tmp"]]);
    assert.deepEqual(init.MountPoints, [{ ContainerPath: "/tmp", SourceVolume: "tmp", ReadOnly: false }]);
    const main = containers.find((c: any) => c.Name === app);
    assert.equal(main.ReadonlyRootFilesystem, true);
    assert.deepEqual(main.DependsOn, [{ ContainerName: "tmp-permissions", Condition: "SUCCESS" }]);
  }
});
test("the API grace period outlasts a start whose first health checks fail", () => {
  // 74 s from task start to a listening app: measured on the 2026-09-19 release (ECS events, app log).
  const startToListening = 74;
  const targetGroup = Object.values(templates.platform.findResources("AWS::ElasticLoadBalancingV2::TargetGroup"));
  assert.equal(targetGroup.length, 1);
  const check = targetGroup[0].Properties;
  // CloudFormation defaults when the template leaves them out (HTTP target groups: 5 checks, 30 s apart).
  const recovery = (check.HealthyThresholdCount ?? 5) * (check.HealthCheckIntervalSeconds ?? 30);
  const behindTheAlb = Object.values(templates.services.findResources("AWS::ECS::Service")).filter(
    (s) => (s.Properties.LoadBalancers ?? []).length > 0,
  );
  assert.equal(behindTheAlb.length, 1);
  const grace = behindTheAlb[0].Properties.HealthCheckGracePeriodSeconds;
  assert.ok(grace >= startToListening + recovery, `grace ${grace}s < ${startToListening}s start + ${recovery}s recovery`);
});
test("DB storage autoscaling stays off without an invalid ceiling", () => {
  // RDS rejects MaxAllocatedStorage that does not exceed AllocatedStorage; omission is 'off'.
  templates.data.hasResourceProperties("AWS::RDS::DBInstance", {
    AllocatedStorage: "20",
    MaxAllocatedStorage: Match.absent(),
  });
});
test("complete synthesis has no dependency cycle and no Services dependency in Platform", () => {
  const assembly = app.synth();
  const platform = assembly.getStackArtifact(stacks.platform.artifactId);
  assert(
    !platform.dependencies.some((d) => d.id === stacks.services.artifactId),
  );
  assert.equal(assembly.stacks.length, 9);
  const bootstrapApp = new cdk.App({outdir:join(directory,"bootstrap")});
  createStacks(bootstrapApp,{account:"1".repeat(12),bootstrapOnly:true,webDirectory:"",
    release:{releaseVersion:"bootstrap",apiImageDigest:"",aiImageDigest:"",aiCatalogVersion:""}});
  assert.equal(bootstrapApp.synth().stacks.length,1);
  assert.equal((assembly.manifest.missing ?? []).length, 0);
});
test("CloudFront reaches the internal ALB over HTTP:80 through the origin-facing prefix list", () => {
  // Defect 2026-09-18: no protocol policy meant match-viewer (HTTPS:443) against an HTTP:80 listener.
  templates.web.hasResourceProperties("AWS::CloudFront::VpcOrigin", {
    VpcOriginEndpointConfig: Match.objectLike({ OriginProtocolPolicy: "http-only", HTTPPort: 80 }),
  });
  // Defect 2026-09-18: the ALB security group had no ingress rule at all.
  const albIngress = ingressRules(templates.network)
    .filter((rule: any) => rule.SourcePrefixListId === "pl-22a6434b");
  assert.equal(albIngress.length, 1);
  assert.equal(albIngress[0].FromPort, 80);
  assert.equal(albIngress[0].ToPort, 80);
});
test("AI task carries the catalog version apps/ai requires in staging", () => {
  const ai = (Object.values(templates.services.findResources("AWS::ECS::TaskDefinition")) as any[])
    .map((t) => t.Properties.ContainerDefinitions[0])
    .find((c) => c.Name === "ai");
  assert.deepEqual(
    ai.Environment.find((e: any) => e.Name === "NULLNULL_CATALOG_VERSION"),
    { Name: "NULLNULL_CATALOG_VERSION", Value: "KTO_KOR_SERVICE_2:4" },
  );
});
test("existing GitHub OIDC provider is referenced, never created", () => {
  for (const t of Object.values(templates))
    for (const type of ["AWS::IAM::OIDCProvider", "Custom::AWSCDKOpenIdConnectProvider"])
      t.resourceCountIs(type, 0);
  const trust = (Object.values(templates.foundation.findResources("AWS::IAM::Role")) as any[])
    .find((r) => r.Properties.RoleName === "nullnull-stg-github-deploy").Properties.AssumeRolePolicyDocument;
  assert.match(JSON.stringify(trust.Statement[0].Principal.Federated),
    /oidc-provider\/token\.actions\.githubusercontent\.com/);
});
test("no Budget resource: the organization SCP denies budgets:*", () => {
  for (const t of Object.values(templates)) t.resourceCountIs("AWS::Budgets::Budget", 0);
});
test("every secret has a project-scoped name", () => {
  const names = Object.values(templates)
    .flatMap((t) => Object.values(t.findResources("AWS::SecretsManager::Secret")) as any[])
    .map((r) => r.Properties.Name);
  assert.equal(names.length, 6);
  for (const name of names) assert.match(name, /^nullnull-stg\//);
});
test("protected stacks never embed a release (classification premise)", () => {
  // An app-only release must leave these templates byte-identical, otherwise every release needs the
  // infra approval path and a rollback (which never redeploys them) leaves them on the newer release.
  for (const name of ["foundation", "network", "data", "platform", "globalWaf", "obs"]) {
    const body = JSON.stringify(templates[name].toJSON());
    for (const marker of ["a".repeat(64), "b".repeat(64), "v0.1.0-rc.1", "KTO_KOR_SERVICE_2:4"])
      assert(!body.includes(marker), `${name} embeds ${marker}`);
  }
});
test("API gate: closed by default, verifier token passes, header never forwarded", () => {
  const vm = require("node:vm");
  const nodeCrypto = require("node:crypto");
  const { GATE_FUNCTION_CODE } = require("../src/staging");
  const token = "t".repeat(43);
  const hash = nodeCrypto.createHash("sha256").update(token).digest("hex");
  const gate = (open: boolean, verifier: string) =>
    vm.runInNewContext(
      GATE_FUNCTION_CODE.replace("${Open}", String(open)).replace("${Verifier}", verifier) + "\nhandler",
      { require: (m: string) => (m === "crypto" ? nodeCrypto : undefined) },
    );
  const request = (value?: string) => ({
    event: { request: { uri: "/api/v1/health/ready", headers: value === undefined ? {} : { "x-nullnull-verifier": { value } } } },
  });
  const closedNoVerifier = gate(false, "");
  assert.equal(closedNoVerifier(request().event).statusCode, 503);
  assert.equal(closedNoVerifier(request(token).event).statusCode, 503, "token useless without configured hash");
  const closed = gate(false, hash);
  assert.equal(closed(request().event).statusCode, 503);
  assert.equal(closed(request("wrong").event).statusCode, 503);
  const passed = closed(request(token).event);
  assert.equal(passed.statusCode, undefined);
  assert.equal(passed.headers["x-nullnull-verifier"], undefined, "token must not reach the origin");
  const response = closed(request().event);
  assert.equal(response.headers["content-type"].value, "application/problem+json");
  assert.equal(JSON.parse(response.body).status, 503);
  const open = gate(true, "");
  const forwarded = open(request(token).event);
  assert.equal(forwarded.uri, "/api/v1/health/ready");
  assert.equal(forwarded.headers["x-nullnull-verifier"], undefined);
  // The synthesized function really is this code with exactly these two substitutions.
  const fn = (Object.values(templates.web.findResources("AWS::CloudFront::Function")) as any[])
    .find((f) => f.Properties.Name === "nullnull-stg-api-gate");
  const [code, vars] = fn.Properties.FunctionCode["Fn::Sub"];
  assert.equal(code, GATE_FUNCTION_CODE);
  assert.deepEqual(Object.keys(vars).sort(), ["Open", "Verifier"]);
  assert.deepEqual(vars.Verifier, { Ref: "VerifierTokenSha256" });
  assert.equal(templates.web.toJSON().Parameters.VerifierTokenSha256.Default, "");
});
test("every role the app creates carries the Nullnull permissions boundary", () => {
  // The CloudFormation execution policy refuses role creation without it (infra/iam/cfn-execution.json).
  let roles = 0;
  for (const t of Object.values(templates))
    for (const role of Object.values(t.findResources("AWS::IAM::Role")) as any[]) {
      roles += 1;
      assert.match(JSON.stringify(role.Properties.PermissionsBoundary), /policy\/NullnullStgRoleBoundary"/);
    }
  assert(roles >= 10, `expected the app's roles, saw ${roles}`);
  const customs = Object.values(templates).flatMap((t) =>
    Object.values(t.toJSON().Resources as Record<string, any>).map((r) => r.Type).filter((type: string) => type.startsWith("Custom::")));
  assert.deepEqual(customs, ["Custom::CDKBucketDeployment"]);
});
process.on("exit", () => rmSync(directory, { recursive: true, force: true }));

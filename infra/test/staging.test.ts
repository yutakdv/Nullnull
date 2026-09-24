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
mkdirSync(join(directory, "covers"));
writeFileSync(join(directory, "covers", "01-synthetic.jpg"), "synthetic cover bytes");
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
  coversDirectory: join(directory, "covers"),
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
test("presigned uploads allow only the submitted browser origin and PUT content type", () => {
  const buckets = Object.values(templates.web.findResources("AWS::S3::Bucket")) as any[];
  assert.equal(buckets.length, 1);
  assert.deepEqual(buckets[0].Properties.CorsConfiguration, {
    CorsRules: [{
      AllowedOrigins: ["https://d54awmnmi4c3z.cloudfront.net"],
      AllowedMethods: ["PUT"],
      AllowedHeaders: ["content-type"],
      MaxAge: 300,
    }],
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
  createStacks(bootstrapApp,{account:"1".repeat(12),bootstrapOnly:true,webDirectory:"",coversDirectory:"",
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
test("the AI task names no external model provider and holds no provider credential (A-064, #337)", () => {
  // Neither this template nor the image sets AI_PROVIDER, so apps/ai runs on its default, NONE
  // (apps/ai/src/nullnull_ai/settings.py). The ai security group allows 443 out to anywhere, so this env
  // and the absent secrets are the only thing between staging and an external model call. A-064 keeps
  // the provider off until the AI-use label ships (#337): turning it on has to be a reviewed change here.
  // The image half is scripts/tests/test_ai_image_provider_off.py: the container's environment also comes from
  // the image, and the infra gate builds with infra/ alone (infra/Dockerfile), so it cannot read apps/ai.
  //
  // Every container of the task AiService runs, not the first one named ai: a sidecar shares the task's
  // network and could make the call as well. And the two other ways a task definition hands a process an
  // environment - a file from S3, and a command line that exports one - are refused outright.
  const resources = templates.services.toJSON().Resources as Record<string, any>;
  const services = Object.entries(resources).filter(
    ([id, r]) => r.Type === "AWS::ECS::Service" && id.startsWith("AiService"),
  );
  assert.equal(services.length, 1, "exactly one AiService");
  const containers = resources[services[0][1].Properties.TaskDefinition.Ref].Properties
    .ContainerDefinitions as any[];
  assert(containers.some((c) => c.Name === "ai"), "the ai container is in the task AiService runs");
  for (const c of containers) {
    const env = (c.Environment ?? []) as any[];
    const secrets = (c.Secrets ?? []) as any[];
    for (const e of env.filter((e) => e.Name === "AI_PROVIDER"))
      assert.equal(e.Value, "NONE", `${c.Name} AI_PROVIDER`);
    for (const name of ["AI_API_KEY", "AI_MODEL_ID"]) {
      assert(!env.some((e) => e.Name === name), `${name} in the ${c.Name} environment`);
      assert(!secrets.some((s) => s.Name === name), `${name} in the ${c.Name} secrets`);
    }
    assert(!secrets.some((s) => s.Name === "AI_PROVIDER"), `AI_PROVIDER in the ${c.Name} secrets`);
    for (const key of ["EnvironmentFiles", "Command", "EntryPoint"])
      assert.equal(c[key], undefined, `${c.Name} ${key}`);
  }
});
test("only the API runs ITEM optimization, and no service turns on a capability that has no source", () => {
  // Owner decision 2026-09-19: the submission build runs ITEM optimization. The value is a literal here so
  // that re-reading staging.ts cannot make this test agree with whatever the file says.
  const containers = [templates.services, templates.migration].flatMap((t) =>
    (Object.values(t.findResources("AWS::ECS::TaskDefinition")) as any[]).flatMap(
      (d) => d.Properties.ContainerDefinitions as any[],
    ),
  );
  const env = (name: string) =>
    containers.find((c) => c.Name === name)?.Environment ?? assert.fail(`no ${name} container`);
  assert.deepEqual(
    env("api").filter((e: any) => e.Name === "FEATURE_OPTIMIZATION_ITEM"),
    [{ Name: "FEATURE_OPTIMIZATION_ITEM", Value: "true" }],
  );
  // The worker runs in the API task; ops, migration and ai never read the capability.
  for (const name of ["ai", "ops", "migration"])
    assert(!env(name).some((e: any) => e.Name === "FEATURE_OPTIMIZATION_ITEM"), name);
  // Live ships with the submission (A-054, owner 2026-09-20), so the API carries the flag ON and the
  // other three never read it - the same shape as optimization above. This assertion is what keeps the
  // decision and the wiring from drifting apart: before A-054 was executed this file asserted the
  // opposite, and the comment it carried ("live and replay have no source yet") had already gone false
  // when V046 promoted SEOUL_CITYDATA and DemoCapabilityQuery dropped live from WITHOUT_A_SOURCE.
  assert.deepEqual(
    env("api").filter((e: any) => e.Name === "FEATURE_LIVE_DATA"),
    [{ Name: "FEATURE_LIVE_DATA", Value: "true" }],
  );
  for (const name of ["ai", "ops", "migration"])
    assert(!env(name).some((e: any) => e.Name === "FEATURE_LIVE_DATA"), `${name} FEATURE_LIVE_DATA`);
  // The code can read replay manifests, but readiness remains UNAVAILABLE until an approved one
  // exists. Only the API, which serves Live, may opt into that runtime fallback.
  assert.deepEqual(
    env("api").filter((e: any) => e.Name === "FEATURE_REPLAY_MODE"),
    [{ Name: "FEATURE_REPLAY_MODE", Value: "true" }],
  );
  for (const name of ["ai", "ops", "migration"])
    assert(!env(name).some((e: any) => e.Name === "FEATURE_REPLAY_MODE"), `${name} FEATURE_REPLAY_MODE`);
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
  // 6 -> 7 with nullnull-stg/seoul-proxy (BA-090). The number moving is how this assertion works:
  // it is what notices a secret arriving that nobody named in a review. Do not delete it, and say
  // here what the new one is when it moves again.
  assert.equal(names.length, 7);
  for (const name of names) assert.match(name, /^nullnull-stg\//);
});
test("the Seoul proxy holds the key, the API task does not, and the allowlist names the proxy", () => {
  const services = templates.services;
  const fn = Object.values(
    services.findResources("AWS::Lambda::Function"),
  ).find((r: any) => r.Properties.FunctionName === "NullnullStgSeoulProxy") as any;
  assert.ok(fn, "the Seoul proxy function exists");

  // The name has to satisfy the deployment role's resource pattern, which is case-sensitive. The
  // gate cannot simulate IAM, so this asserts the shape the checked-in policy allows instead.
  assert.match(fn.Properties.FunctionName, /^NullnullStg/);
  // Outside the VPC: inside it would need a NAT, and A-029's cost plan does not have one.
  assert.equal(fn.Properties.VpcConfig, undefined);
  // The function is handed a secret NAME to read at runtime, never a key. If the key were passed as
  // an environment variable it would sit in the template, which is the thing the KTO secret and the
  // verifier hash both avoid.
  assert.deepEqual(Object.keys(fn.Properties.Environment.Variables), ["SECRET_ID"]);
  // It reads THAT secret: the value is an ImportValue of the Foundation stack's Seoul secret,
  // not a literal - so this matches the resource it points at rather than a name string.
  assert.match(JSON.stringify(fn.Properties.Environment.Variables), /ImportValue.*Seoul/);
  // Inline code, so this assertion reads what will actually run: it must reach exactly one upstream
  // host, and it must not print the URL - the URL is where the key is.
  const code = fn.Properties.Code.ZipFile as string;
  assert.match(code, /openapi\.seoul\.go\.kr:8088/);
  // The three guarantees this hop owes, because the Java client cannot make them on its behalf:
  // it follows no redirect, it does not buffer an unbounded body, and it refuses to run with the
  // placeholder credential the secret is created with.
  assert.match(code, /redirect: 'error'/);
  assert.match(code, /size > MAX_BYTES/);
  assert.match(code, /seoul_proxy_secret_incomplete/);
  assert.match(code, /console\.error\('seoul_proxy_secret_unavailable'\)/);
  assert.equal(code.includes("failure.message"), false);
  // And it re-reads the secret, so a rotated key or a revoked token reaches a warm container.
  assert.match(code, /Date\.now\(\) - cachedAt < TTL_MS/);
  assert.equal(code.includes("console.error('seoul_proxy_upstream_failed name='"), true);
  assert.equal(/console\.(log|error|warn)\([^)]*upstream[^)]*\)/.test(code.replace(
    "console.error('seoul_proxy_upstream_failed name=' + (failure && failure.name));", "")), false);

  const api = Object.values(services.findResources("AWS::ECS::TaskDefinition"))
    .find((r: any) => r.Properties.Family === "nullnull-stg-api") as any;
  const container = api.Properties.ContainerDefinitions.find((c: any) => c.Name === "api");
  const environment = JSON.stringify(container.Environment);
  const secrets = JSON.stringify(container.Secrets);

  // THE SILENT MISROUTE. If the allowlist still named the provider while the base URL named the
  // proxy, a request built for the proxy - with no key in its path - would be sent to Seoul, and
  // what Seoul does with a keyless request is not something this repository has measured.
  assert.equal(environment.includes("openapi.seoul.go.kr"), false);
  assert.match(environment, /SEOUL_BASE_URL/);
  assert.match(environment, /SEOUL_ALLOWED_HOST/);
  // The task receives the proxy token and never the Seoul key: a value that never arrives cannot leak.
  assert.match(secrets, /SEOUL_PROXY_TOKEN/);
  assert.match(secrets, /proxyToken/);
  assert.equal(secrets.includes("apiKey"), false);
});
test("the Seoul collector ops task receives the proxy token without its API key", () => {
  const definition = Object.values(templates.migration.findResources("AWS::ECS::TaskDefinition"))
    .find((r: any) => r.Properties.Family === "nullnull-stg-ops") as any;
  const container = definition.Properties.ContainerDefinitions.find((c: any) => c.Name === "ops");
  const secrets = JSON.stringify(container.Secrets);
  assert.match(secrets, /SEOUL_PROXY_TOKEN/);
  assert.equal(secrets.includes("apiKey"), false);
  templates.services.hasOutput("SeoulProxyUrl", {});
  templates.services.hasOutput("SeoulProxyHost", {});
});
test("the reviewed Seoul area refreshes inside the existing API task", () => {
  const matching = Object.values(templates.services.findResources("AWS::Scheduler::Schedule"))
    .filter((r: any) => r.Properties.Name === "nullnull-stg-seoul-live-refresh") as any[];
  assert.equal(matching.length, 0, "no recurring Fargate charge for Live collection");
  const api = Object.values(templates.services.findResources("AWS::ECS::TaskDefinition"))
    .find((r: any) => r.Properties.Family === "nullnull-stg-api") as any;
  const container = api.Properties.ContainerDefinitions.find((c: any) => c.Name === "api");
  assert.match(JSON.stringify(container.Environment), /NULLNULL_LIVE_SCHEDULE_ENABLED.*true/);
});
test("a missing or failed Seoul collection reaches the alarm topic", () => {
  templates.obs.hasResourceProperties("AWS::Logs::MetricFilter", {
    FilterPattern: '"seoul_live_collect live=true"',
    MetricTransformations: [Match.objectLike({ MetricName: "SeoulLiveCollectOk", DefaultValue: 0 })],
  });
  templates.obs.hasResourceProperties("AWS::CloudWatch::Alarm", {
    MetricName: "SeoulLiveCollectOk",
    Period: 60,
    EvaluationPeriods: 12,
    ComparisonOperator: "LessThanThreshold",
    Threshold: 1,
    TreatMissingData: "breaching",
    AlarmActions: [Match.anyValue()],
  });
  templates.obs.hasResourceProperties("AWS::Logs::MetricFilter", {
    FilterPattern: '"seoul_live_collect_failed"',
  });
  templates.obs.hasResourceProperties("AWS::CloudWatch::Alarm", {
    MetricName: "SeoulLiveCollectFailures",
    EvaluationPeriods: 1,
    TreatMissingData: "notBreaching",
    AlarmActions: [Match.anyValue()],
  });
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
  // Two bucket deployments (the web bundle and the #183 covers) share CDK's one singleton handler, so the set of
  // custom resource types is still exactly the one this app allows.
  assert.deepEqual([...new Set(customs)], ["Custom::CDKBucketDeployment"]);
  assert.equal(customs.length, 2, "the web bundle and the covers");
});
const {
  FORECAST_SCHEDULE_END,
  FORECAST_SCHEDULE_RATE_HOURS,
  FORECAST_MISSING_PERIOD_HOURS,
  FORECAST_MISSING_PERIODS,
  FORECAST_DEMO_PLACES,
  FORECAST_MAIN,
  FORECAST_DONE_PHRASE,
  DEMO_REFRESH_FAILED_PHRASE,
  FORECAST_EVIDENCE_TAG,
  FORECAST_EMPTY_TERM,
  INT04_CONTENT_ID,
  DETAIL_MAIN,
  DETAIL_SCHEDULE_RATE_DAYS,
  OPS_ALARM_NAMES,
  METRIC_NAMESPACE,
} = require("../src/staging");
const schedules = () =>
  Object.values(
    templates.migration.findResources("AWS::Scheduler::Schedule"),
  ) as any[];
const schedule = (name = "nullnull-stg-forecast-refresh") => {
  const found = schedules().filter((s) => s.Properties.Name === name);
  assert.equal(found.length, 1, `one schedule named ${name}`);
  return found[0];
};
// The literal halves of the target input; the other halves are CloudFormation references. Joined with a
// newline, which no literal half contains, so a match cannot straddle a reference.
const scheduleInput = (name?: string) =>
  (schedule(name).Properties.Target.Input["Fn::Join"][1] as any[])
    .filter((p) => typeof p === "string")
    .join("\n");
test("the forecast refresh runs every 12 h and the schedule itself stops at the judging expiry", () => {
  // Not a task-side check: what has to stop is the calling, and the same instant is staging_operator.py's
  // EXPIRY (scripts/tests/test_ops_alarm_metric_filters.py compares the two files).
  templates.migration.hasResourceProperties("AWS::Scheduler::Schedule", {
    Name: "nullnull-stg-forecast-refresh",
    ScheduleExpression: `rate(${FORECAST_SCHEDULE_RATE_HOURS} hours)`,
    EndDate: FORECAST_SCHEDULE_END.toISOString(),
    State: "ENABLED",
    FlexibleTimeWindow: { Mode: "OFF" },
  });
});
test("the schedule's run carries the standing KTO approval, the demo places and the forecast main", () => {
  // A-044 is visible here or the schedule runs no KTO call at all: the approval is read from the process
  // environment by KtoDemoRefreshCommand, and nothing else in the task supplies it.
  const input = scheduleInput();
  for (const [name, value] of [
    ["LOADER_MAIN", FORECAST_MAIN],
    ["NULLNULL_DEMO_PLACES", FORECAST_DEMO_PLACES],
    ["APP_CONTEST_PROFILE", "2026_KTO_WEBAPP"],
    ["NULLNULL_KTO_FORECAST_SMOKE_APPROVED", "true"],
  ])
    assert(
      input.includes(`{"Name":"${name}","Value":"${value}"}`),
      `override ${name}=${value} missing from the schedule input`,
    );
  assert(
    input.includes('"Name":"NULLNULL_OPERATIONS_TARGET","Value":"postgresql://'),
    "the run must name the database it is allowed to write",
  );
  assert(
    input.includes('"Name":"ops"') && input.includes('"LaunchType":"FARGATE"'),
    "the override must address the ops container of a Fargate run",
  );
});
test("every key of the schedule's RunTask input is PascalCase, the shape Scheduler validates", () => {
  // Scheduler checks universal-target input against the SDK request shape with PascalCase member names.
  // camelCase keys (the ECS JSON API's and boto3's shape) synthesize fine and are refused only at CREATE:
  // "Request payload is missing the following field(s): TaskDefinition" (run 35457509371).
  for (const s of schedules()) {
    const input = (s.Properties.Target.Input["Fn::Join"][1] as any[])
      .filter((p) => typeof p === "string")
      .join("\n");
    const keys = [...input.matchAll(/"([A-Za-z]+)":/g)].map((m) => m[1]);
    assert(keys.includes("TaskDefinition") && keys.includes("Cluster"), `${s.Properties.Name} names no task`);
    const lower = keys.filter((k) => !/^[A-Z]/.test(k));
    assert.deepEqual(lower, [], `${s.Properties.Name} has non-PascalCase keys`);
  }
});
test("the schedule follows the release: it refers to this stack's ops task definition, not a fixed ARN", () => {
  // The whole reason the schedule lives in Migration (an app stack). A literal ARN would keep calling the
  // revision that existed when the schedule was written, and drift from the deployed release in silence.
  const ops = Object.entries(
    templates.migration.findResources("AWS::ECS::TaskDefinition"),
  ).find(([, r]: any) => r.Properties.Family === "nullnull-stg-ops");
  assert(ops, "the ops task definition");
  for (const s of schedules()) {
    const refs = (s.Properties.Target.Input["Fn::Join"][1] as any[])
      .filter((p) => p && typeof p === "object" && p.Ref)
      .map((p) => p.Ref);
    assert(
      refs.includes(ops![0]),
      `${s.Properties.Name} must reference ${ops![0]}, referenced ${JSON.stringify(refs)}`,
    );
  }
});
test("the schedule's role may run only the ops family, only in this cluster, and pass only its task roles", () => {
  const statements = (
    Object.entries(templates.migration.findResources("AWS::IAM::Policy")).find(
      ([k]) => k.includes("Scheduler"),
    )![1] as any
  ).Properties.PolicyDocument.Statement;
  const run = statements.find((s: any) => s.Action === "ecs:RunTask");
  assert(run, "the role runs tasks");
  // Built, not spelled: a 12-digit literal here is an account id to the repository's own scan.
  assert.equal(
    run.Resource,
    `arn:aws:ecs:ap-northeast-2:${"1".repeat(12)}:task-definition/nullnull-stg-ops:*`,
  );
  assert(run.Condition.ArnEquals["ecs:cluster"], "scoped to the cluster");
  const pass = statements.find((s: any) => s.Action === "iam:PassRole");
  assert.equal(
    pass.Condition.StringEquals["iam:PassedToService"],
    "ecs-tasks.amazonaws.com",
  );
  assert.equal(pass.Resource.length, 2, "the ops task and execution roles only");
});
test("a forecast that stopped refreshing alarms before the data is stale; a failed run alarms at once", () => {
  // RunTask returns when the task is placed and never reads its exit code, so the signal is the task's
  // own success line - and its absence is the only thing that also catches a schedule that never ran.
  templates.obs.hasResourceProperties("AWS::Logs::MetricFilter", {
    FilterPattern: `"${FORECAST_DONE_PHRASE}" "failed=0"`,
    MetricTransformations: [
      Match.objectLike({
        MetricName: "ForecastRefreshOk",
        MetricNamespace: METRIC_NAMESPACE,
        DefaultValue: 0,
      }),
    ],
  });
  templates.obs.hasResourceProperties("AWS::CloudWatch::Alarm", {
    MetricName: "ForecastRefreshOk",
    ComparisonOperator: "LessThanThreshold",
    Threshold: 1,
    EvaluationPeriods: FORECAST_MISSING_PERIODS,
    Period: FORECAST_MISSING_PERIOD_HOURS * 3600,
    TreatMissingData: "breaching",
    AlarmActions: [Match.anyValue()],
  });
  // Periods sit on the clock, not on the last success, so the alarm fires between N*P and N*P+P after it.
  // The LATEST of those has to precede PT24H by enough to act on, and the EARLIEST must not be reachable
  // between two healthy runs. Each bound alone permits a useless alarm; the old 3 x 6 h passed the first
  // test written here and still fired, at worst, at the instant a set went stale.
  const windowHours = FORECAST_MISSING_PERIOD_HOURS * FORECAST_MISSING_PERIODS;
  const latestHours = windowHours + FORECAST_MISSING_PERIOD_HOURS;
  assert(
    24 - latestHours >= 4,
    `fires up to ${latestHours} h after the last success: ${24 - latestHours} h before PT24H is not a warning`,
  );
  assert(
    windowHours >=
      FORECAST_SCHEDULE_RATE_HOURS + FORECAST_MISSING_PERIOD_HOURS,
    `${windowHours} h can be emptied by two healthy runs ${FORECAST_SCHEDULE_RATE_HOURS} h apart`,
  );
  templates.obs.hasResourceProperties("AWS::Logs::MetricFilter", {
    FilterPattern: `"${DEMO_REFRESH_FAILED_PHRASE}"`,
  });
  templates.obs.hasResourceProperties("AWS::CloudWatch::Alarm", {
    MetricName: "DemoRefreshFailures",
    ComparisonOperator: "GreaterThanOrEqualToThreshold",
    Threshold: 1,
    // One period, and an empty window is not a failure - the opposite of the alarm above.
    EvaluationPeriods: 1,
    TreatMissingData: "notBreaching",
    AlarmActions: [Match.anyValue()],
  });
  // The run that prints failed=0 and stored nothing: invisible to both alarms above. One phrase, and for
  // the INT-04 place only - other places may have no forecast at all, which is not an incident.
  templates.obs.hasResourceProperties("AWS::Logs::MetricFilter", {
    FilterPattern: `"${FORECAST_EVIDENCE_TAG} contentId=${INT04_CONTENT_ID} ${FORECAST_EMPTY_TERM}"`,
  });
  assert(FORECAST_DEMO_PLACES.split(",").some((p: string) => p.startsWith(INT04_CONTENT_ID + ":")),
    "the INT-04 place is not refreshed, so its alarm could never fire");
  templates.obs.hasResourceProperties("AWS::CloudWatch::Alarm", {
    MetricName: "ForecastRefreshEmpty",
    ComparisonOperator: "GreaterThanOrEqualToThreshold",
    Threshold: 1,
    EvaluationPeriods: 1,
    TreatMissingData: "notBreaching",
    AlarmActions: [Match.anyValue()],
  });
});
test("the detail snapshot the forecast is built from is renewed on its own schedule, before it lapses", () => {
  // A forecast request needs a detail snapshot younger than the registry's 604800 s; a forecast-only
  // schedule fails from the seventh day of judging onward. The cadence's pairing with that lifetime is
  // pinned against V007 by scripts/tests/test_ops_alarm_metric_filters.py.
  templates.migration.hasResourceProperties("AWS::Scheduler::Schedule", {
    Name: "nullnull-stg-detail-refresh",
    ScheduleExpression: `rate(${DETAIL_SCHEDULE_RATE_DAYS} days)`,
    EndDate: FORECAST_SCHEDULE_END.toISOString(),
    State: "ENABLED",
  });
  const input = scheduleInput("nullnull-stg-detail-refresh");
  for (const [name, value] of [
    ["LOADER_MAIN", DETAIL_MAIN],
    ["NULLNULL_DEMO_PLACES", FORECAST_DEMO_PLACES],
    ["NULLNULL_KTO_SMOKE_APPROVED", "true"],
  ])
    assert(
      input.includes(`{"Name":"${name}","Value":"${value}"}`),
      `override ${name}=${value} missing from the detail schedule`,
    );
  // Each schedule carries only its own mode's approval: the forecast one never approves a detail call.
  assert(!scheduleInput().includes("NULLNULL_KTO_SMOKE_APPROVED"));
  assert(!input.includes("NULLNULL_KTO_FORECAST_SMOKE_APPROVED"));
  assert.equal(schedules().length, 2, "forecast and detail, nothing else");
});
test("every ops.alarm name has a metric filter and an alarm that notifies", () => {
  // OpsAlarm's javadoc has said "a metric filter matches the quoted phrase" since BA-072; none existed.
  assert(OPS_ALARM_NAMES.length >= 5, "the declared vocabulary");
  for (const name of OPS_ALARM_NAMES) {
    templates.obs.hasResourceProperties("AWS::Logs::MetricFilter", {
      FilterPattern: `"ops.alarm name=${name}"`,
    });
    const metric = "OpsAlarm" + name.split("_").map((w: string) => w[0] + w.slice(1).toLowerCase()).join("");
    templates.obs.hasResourceProperties("AWS::CloudWatch::Alarm", {
      MetricName: metric,
      Threshold: 1,
      EvaluationPeriods: 1,
      TreatMissingData: "notBreaching",
      AlarmActions: [Match.anyValue()],
    });
  }
});
test("the topic policy lets this account's CloudWatch alarms publish (enforceSSL replaces the default policy)", () => {
  // BA-072-T7 drill: five alarms reached ALARM and every SNS action failed, because the enforceSSL policy
  // left no Allow for the cloudwatch service principal. Nothing else in the template grants it.
  const policies = Object.values(templates.obs.findResources("AWS::SNS::TopicPolicy")) as any[];
  const statements = policies.flatMap((p) => p.Properties.PolicyDocument.Statement);
  const allow = statements.filter(
    (s: any) =>
      s.Effect === "Allow" &&
      s.Principal?.Service === "cloudwatch.amazonaws.com" &&
      ([] as string[]).concat(s.Action).includes("sns:Publish"),
  );
  assert.equal(allow.length, 1, "exactly one Allow for CloudWatch to publish");
  const c = allow[0].Condition ?? {};
  assert(c.StringEquals?.["aws:SourceAccount"], "scoped to this account");
  assert.match(JSON.stringify(c.ArnLike?.["aws:SourceArn"] ?? ""), /:alarm:\*/, "scoped to alarms");
  assert(
    statements.some((s: any) => s.Effect === "Deny" && JSON.stringify(s.Condition ?? {}).includes("aws:SecureTransport")),
    "the SSL-only deny stays",
  );
});
test("every alarm publishes to the one topic the subscribe script subscribes, and no address is in the template", () => {
  // An alarm with no action is the exact failure this work exists to remove: it fires, and the firing
  // is indistinguishable from silence. Who receives the topic is decided by
  // scripts/aws/staging-alarm-subscribe.sh (A-037), never by a literal here.
  const json = templates.obs.toJSON();
  const topics = Object.keys(templates.obs.findResources("AWS::SNS::Topic"));
  assert.equal(topics.length, 1, "one alarm topic");
  const alarms = Object.values(
    templates.obs.findResources("AWS::CloudWatch::Alarm"),
  ) as any[];
  assert(alarms.length >= 10, `expected the alarm set, saw ${alarms.length}`);
  for (const a of alarms)
    assert.deepEqual(
      a.Properties.AlarmActions,
      [{ Ref: topics[0] }],
      `${a.Properties.MetricName} notifies nobody`,
    );
  assert.equal(
    Object.keys(templates.obs.findResources("AWS::SNS::Subscription")).length,
    0,
    "the subscription has one owner and it is the operator script",
  );
  assert.doesNotMatch(
    JSON.stringify(json),
    /[A-Za-z0-9._%-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/,
    "the receiver's address belongs to the operator's ignored settings, not to this repository",
  );
});
test("BA-082 the API task may write covers/user and quarantine, and nothing else in the bucket", () => {
  // The bucket this grant is on also holds the application bundle, so the interesting assertion is
  // not that the task CAN write - it is what it cannot. A bucket-wide grant here would let the API
  // replace index.html.
  const policies = Object.values(templates.services.findResources("AWS::IAM::Policy")) as any[];
  const statements = policies.flatMap(
    (p) => p.Properties.PolicyDocument.Statement as any[],
  );
  const s3Writes = statements.filter((st) =>
    ([] as string[]).concat(st.Action ?? []).some((a: string) => a.startsWith("s3:")),
  );
  assert.equal(s3Writes.length, 2, "one statement per prefix");

  const resourcesOf = (st: any) =>
    JSON.stringify(([] as any[]).concat(st.Resource ?? []));
  // Every s3 resource this task is given ends in one of the two prefixes. Written as a positive
  // check over ALL of them rather than as two lookups, so a third statement added later cannot
  // slip past by simply not being one of the two we asked about.
  for (const st of s3Writes) {
    const json = resourcesOf(st);
    assert.ok(
      json.includes("quarantine/*") || json.includes("covers/user/*"),
      `s3 grant outside the two prefixes: ${json}`,
    );
    // And never the bucket itself: arnForObjects keeps the /* , a bucket ARN would not.
    assert.ok(!/"[^"]*WebBucket[^"]*"\s*\]?\s*$/.test(json.replace(/covers\/user\/\*|quarantine\/\*/g, "")),
      `s3 grant names the bucket rather than a prefix: ${json}`);
  }

  // The published prefix is write-only: reading and deleting covers is not this task's business.
  const published = s3Writes.find((st) => resourcesOf(st).includes("covers/user/*"));
  assert.deepEqual(([] as string[]).concat(published.Action), ["s3:PutObject"]);
});

test("BA-082 only the ops task can list and delete versions of user covers", () => {
  const policies = Object.values(templates.migration.findResources("AWS::IAM::Policy")) as any[];
  const statements = policies.flatMap((p) => p.Properties.PolicyDocument.Statement as any[]);
  const withAction = (action: string) => statements.filter((st) =>
    ([] as string[]).concat(st.Action ?? []).includes(action));
  const list = withAction("s3:ListBucketVersions");
  const remove = withAction("s3:DeleteObjectVersion");
  assert.equal(list.length, 1);
  assert.equal(remove.length, 1);
  assert.deepEqual(list[0].Condition, { StringLike: { "s3:prefix": ["covers/user/*"] } });
  assert.match(JSON.stringify(list[0].Resource), /WebBucket/);
  assert.doesNotMatch(JSON.stringify(list[0].Resource), /covers\/user\/\*/);
  assert.match(JSON.stringify(remove[0].Resource), /covers\/user\/\*/);
  for (const st of statements) {
    const actions = ([] as string[]).concat(st.Action ?? []);
    if (actions.some((a) => a.startsWith("s3:DeleteObject"))) {
      assert.deepEqual(actions, ["s3:DeleteObjectVersion"]);
      assert.match(JSON.stringify(st.Resource), /covers\/user\/\*/);
    }
  }
  const apiPolicies = Object.values(templates.services.findResources("AWS::IAM::Policy")) as any[];
  assert.doesNotMatch(JSON.stringify(apiPolicies), /s3:DeleteObjectVersion/);
  templates.migration.hasResourceProperties("AWS::ECS::TaskDefinition", {
    ContainerDefinitions: Match.arrayWith([Match.objectLike({
      Name: "ops",
      Environment: Match.arrayWith([
        Match.objectLike({ Name: "NULLNULL_UPLOAD_S3_BUCKET" }),
        Match.objectLike({ Name: "NULLNULL_UPLOAD_S3_REGION" }),
        Match.objectLike({ Name: "NULLNULL_UPLOAD_S3_PUBLIC_BASE_URL" }),
      ]),
    })]),
  });
});

test("BA-082 the edge refuses the quarantine prefix outright", () => {
  // Uploads live in the same bucket the distribution serves, so "not linked to" is not the same as
  // "not reachable". The SPA rewrite hides extension-less keys by accident (they become
  // /index.html); a quarantined .jpg would be served without this.
  const fns = Object.values(
    templates.web.findResources("AWS::CloudFront::Function"),
  ) as any[];
  const spa = fns.find(
    (f) => f.Properties.Name === "nullnull-stg-spa-rewrite",
  );
  assert.ok(spa, "the SPA rewrite function exists");
  const code = spa.Properties.FunctionCode as string;
  assert.match(code, /indexOf\('\/quarantine'\)===0\)return \{statusCode:404\}/);
  // The refusal comes BEFORE the rewrite, or the rewrite would have already changed the uri.
  assert.ok(
    code.indexOf("/quarantine") < code.indexOf("index.html"),
    "the quarantine refusal must precede the rewrite",
  );
});

test("the curated covers are served under /covers/ of the web distribution, kept, cached and invalidated alone", () => {
  // #183. The URLs in ops/curated-posts.json are <PublicUrl>/covers/<file>; media_assets takes only absolute https.
  const deployments = Object.values(
    templates.web.findResources("Custom::CDKBucketDeployment"),
  ) as any[];
  const covers = deployments.filter(
    (d) => d.Properties.DestinationBucketKeyPrefix === "covers/",
  );
  assert.equal(covers.length, 1, "one covers deployment");
  const c = covers[0].Properties;
  // A published post keeps pointing at its cover: nothing a later release does may delete one.
  assert.equal(c.Prune, false);
  assert.equal(c.RetainOnDelete, true);
  assert.deepEqual(c.DistributionPaths, ["/covers/*"]);
  assert.equal(c.SystemMetadata["cache-control"], "public, max-age=86400");
  assert.deepEqual(c.DistributionId, deployments.find((d) => d !== covers[0]).Properties.DistributionId);
  // The web bundle's own deployment is unchanged: no prefix, no-cache, the whole site invalidated.
  const bundle = deployments.find((d) => d !== covers[0]).Properties;
  assert.equal(bundle.DestinationBucketKeyPrefix, undefined);
  assert.equal(bundle.SystemMetadata["cache-control"], "no-cache");
});
process.on("exit", () => rmSync(directory, { recursive: true, force: true }));

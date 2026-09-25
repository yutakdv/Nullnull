import * as cdk from "aws-cdk-lib";
import {
  aws_ec2 as ec2,
  aws_ecs as ecs,
  aws_ecr as ecr,
  aws_rds as rds,
  aws_iam as iam,
  aws_s3 as s3,
  aws_secretsmanager as sm,
  aws_elasticloadbalancingv2 as elb,
  aws_logs as logs,
  aws_cloudfront as cf,
  aws_cloudfront_origins as origins,
  aws_wafv2 as waf,
  aws_sns as sns,
  aws_cloudwatch as cw,
  aws_cloudwatch_actions as actions,
  aws_dynamodb as ddb,
  aws_s3_deployment as deploy,
  aws_scheduler as scheduler,
  aws_scheduler_targets as schedulerTargets,
  aws_lambda as lambda,
} from "aws-cdk-lib";
import { readFileSync } from "node:fs";
import { join } from "node:path";
export interface Release {
  releaseVersion: string;
  apiImageDigest: string;
  aiImageDigest: string;
  // apps/ai refuses to start in staging without NULLNULL_CATALOG_VERSION (settings.py).
  aiCatalogVersion: string;
}
// One file for the GitHub OIDC identity so the role trust and validate-oidc-trust.mjs cannot drift.
// The repository opted into immutable subjects (owner/repository IDs in `sub`), so the name-only form
// "repo:yutakdv/Nullnull:..." is never issued and must not be trusted.
export const githubOidc: {
  provider: string;
  audience: string;
  deploySubjects: string[];
  publishSubjects: string[];
} = JSON.parse(readFileSync(join(__dirname, "..", "..", "github-oidc.json"), "utf8"));
// AWS-managed "com.amazonaws.global.cloudfront.origin-facing" list in ap-northeast-2 (46 entries,
// read 2026-09-18). CloudFront VPC origin traffic reaches the internal ALB only if its SG allows this.
export const CLOUDFRONT_ORIGIN_FACING_PREFIX_LIST = "pl-22a6434b";
// Runtime switches the release operator decides (committed so CD redeploys what is live, never a default).
export const stagingConfig: { catalogPublicEnabled: boolean } = (() => {
  const value = JSON.parse(readFileSync(join(__dirname, "..", "..", "staging.config.json"), "utf8"));
  if (typeof value?.catalogPublicEnabled !== "boolean" || Object.keys(value).length !== 1)
    throw new Error("staging.config.json must be exactly {catalogPublicEnabled: boolean}");
  return value;
})();
// Non-default bootstrap qualifier: another project's leftover cdk-hnb659fds-assets bucket exists in
// us-east-1, and a default-qualifier bootstrap there would collide with (or update) someone else's.
export const BOOTSTRAP_QUALIFIER = "nnstg";
// Managed policies created once by scripts/aws/staging-iam.py from infra/iam/*.json.
export const ROLE_BOUNDARY_POLICY = "NullnullStgRoleBoundary";
// Metric namespace for everything this app publishes from its own log lines.
export const METRIC_NAMESPACE = "Nullnull/Staging";
// The forecast refresh schedule (A-044). The end instant is staging_operator.py's EXPIRY, not a second
// date: the service ends 2026-10-31 23:59:59 KST (A-069; the stack `Expiry` tag stays 2026-10-25 on purpose,
// see A-069) and nothing here may outlive it.
export const FORECAST_SCHEDULE_END = new Date("2026-10-31T14:59:59Z");
// Every 12 h. A run renews the sets that lapse within KtoDemoRefresh.FORECAST_RENEW_BEFORE of its start,
// six hours longer than this cadence, so the set one run fetched is renewed by the next as long as a
// run's call comes less than six hours later after its tick than the previous run's did. Lateness is
// the scheduler's delivery, which maxEventAge below bounds (an older invocation is dropped, not
// delivered late), then the Fargate start and the places ahead - minutes, bounded by nothing here. Equal numbers
// were the defect (#361): that set lapsed exactly one window after the next run's tick, and whichever
// run started faster after its tick decided whether it was renewed. The relation, not the numbers, is
// the rule; scripts/tests/test_ops_alarm_metric_filters.py holds the two files to it.
export const FORECAST_SCHEDULE_RATE_HOURS = 12;
// The missing-refresh alarm's window: 18 periods of 1 h. The reasoning is at the alarm; these are
// constants so the infra test can state the bounds without restating the arithmetic.
export const FORECAST_MISSING_PERIOD_HOURS = 1;
export const FORECAST_MISSING_PERIODS = 18;
// The demo places, as staging_operator.py's `places` input spells them: contentId:contentTypeId.
export const FORECAST_DEMO_PLACES = "126508:12,128611:12";
// OPS_TASKS['kto-demo-forecast'] and ['kto-demo-detail'] in scripts/aws/staging_operator.py name these
// same main classes, with these same approval variables.
export const FORECAST_MAIN =
  "io.nullnull.catalog.infrastructure.kto.KtoDemoForecastRefreshMain";
export const DETAIL_MAIN =
  "io.nullnull.catalog.infrastructure.kto.KtoDemoDetailRefreshMain";
// A forecast request is built from a detailCommon2 snapshot, and the registry stales that snapshot after
// 604800 s (V007__sources.sql). Once it lapses the forecast refresh has nothing to ask with and ends
// NO_VERIFIED_KTO_MAPPING (KtoDemoRefresh.refreshForecast), so a forecast-only schedule would fail from
// the seventh day of the judging period onward. Every 5 days, with
// KtoDemoRefresh.DETAIL_RENEW_BEFORE longer than that by a day, so each run renews the snapshot the run
// before it fetched as long as its call is not a day later after its tick than that run's (the same
// lateness as above). "5 + 2 = the 7 the registry allows" was the defect (#361): it put that snapshot
// exactly on the next run's renewal boundary, where a skipped renewal left about three days without a
// mapping. The same test file holds this relation.
export const DETAIL_SCHEDULE_RATE_DAYS = 5;
// What KtoDemoRefresh prints when a forecast run touched every place without one failing. The mode token
// is lower case (KtoDemoRefresh.name(Mode)); a filter quoting "mode=FORECAST" would never match a line.
export const FORECAST_DONE_PHRASE = "KTO_DEMO_REFRESH_DONE mode=forecast";
// What KtoDemoRefreshCommand throws when either mode fails. It carries no mode token - the detail and
// the forecast refresh share the prefix - so the alarm on it is named for both.
export const DEMO_REFRESH_FAILED_PHRASE = "KTO demo refresh failed:";
// A forecast refresh the provider answered with nothing is REFRESHED, not FAILED (KtoDemoRefresh prints
// its evidence as "coverage=0"), so it prints failed=0 and feeds the success metric. For most places
// that is the truth - KTO does not forecast every attraction - but for the INT-04 place it means the
// judged flow has no evidence to rest on. So the alarm names that one place: the phrase is the whole
// evidence prefix KtoDemoRefresh.Outcome.lines() builds, "<tag> contentId=<id> coverage=0", which a
// set with no points and no set at all both print. The Python test pins every piece of it.
export const FORECAST_EVIDENCE_TAG = "KTO_DEMO_REFRESH_EVIDENCE";
export const FORECAST_EMPTY_TERM = "coverage=0";
// 경복궁, the INT-04 place (owner sheet step 1: "반드시 넣는다"). It must be one of the demo places.
export const INT04_CONTENT_ID = "126508";
// io.nullnull.operations.application.OpsAlarm.Name, whose phrase() is "ops.alarm name=<NAME>" and whose
// javadoc says a metric filter quotes it. Until this file listed them, nothing did: the vocabulary
// existed and reached no one. The same Python test compares this list with the enum both ways.
export const OPS_ALARM_NAMES = [
  "DELETION_PARTIAL_FAILED",
  "DELETION_FAILED",
  "JOB_LEASE_RETAKEN",
  "JOB_DEAD_LETTER",
  "DELETION_RECEIPT_EXPIRED_UNFINISHED",
];
// CloudFront Function (cloudfront-js-2.0, crypto.createHash sha256 supported) for /api/*. ${Open} and
// ${Verifier} are CloudFormation Fn::Sub variables; the code itself must not contain "${".
// The Seoul live-area proxy. It exists because the provider is plain HTTP on port 8088 with the API
// key in the URL PATH, and ProviderHttpClient refuses anything that is not HTTPS or loopback.
//
// IT INJECTS THE KEY; IT IS NOT A PASS-THROUGH. If the caller sent the key, the key would sit in the
// path of every request and the path is the part access logs record - BA-070-T2 pins only that the
// query string stays out of them. Simplifying this into a pass-through removes the only reason it
// exists.
//
// It is also not an open relay: one upstream host, one path template, and a shared token it checks
// before it calls anything. No log line here carries the URL, the key or the token - not even on
// failure, because a failure is exactly when someone prints the request.
export const SEOUL_PROXY_CODE = [
  "const { SecretsManagerClient, GetSecretValueCommand } = require('@aws-sdk/client-secrets-manager');",
  "const client = new SecretsManagerClient({});",
  "const TTL_MS = 300000;",
  "const MAX_BYTES = 2097152;",
  "let cached = null;",
  "let cachedAt = 0;",
  "async function secret() {",
  // A warm container would otherwise hold the first value for its whole life: a rotated key would
  // never be picked up and a revoked proxy token would keep being accepted.
  "  if (cached && Date.now() - cachedAt < TTL_MS) return cached;",
  "  const out = await client.send(new GetSecretValueCommand({ SecretId: process.env.SECRET_ID }));",
  "  const parsed = JSON.parse(out.SecretString);",
  // The secret is created with apiKey:"" so the operator can put the real one. Sending that blank
  // upstream is a silent failure - Seoul decides what a keyless request means, and we do not know.
  "  if (!parsed || typeof parsed.apiKey !== 'string' || !parsed.apiKey",
  "      || typeof parsed.proxyToken !== 'string' || !parsed.proxyToken) {",
  "    throw new Error('seoul_proxy_secret_incomplete');",
  "  }",
  "  cached = parsed; cachedAt = Date.now();",
  "  return cached;",
  "}",
  "function timingSafeEqual(a, b) {",
  "  if (typeof a !== 'string' || a.length !== b.length) return false;",
  "  let differing = 0;",
  "  for (let i = 0; i < a.length; i++) differing |= a.charCodeAt(i) ^ b.charCodeAt(i);",
  "  return differing === 0;",
  "}",
  "exports.handler = async (event) => {",
  "  const path = (event && event.rawPath) || '';",
  "  const headers = (event && event.headers) || {};",
  "  let apiKey, proxyToken;",
  "  try { ({ apiKey, proxyToken } = await secret()); }",
  "  catch (failure) {",
  // JSON.parse error messages may quote malformed secret bytes, including key material.
  "    console.error('seoul_proxy_secret_unavailable');",
  "    return { statusCode: 503, body: '{\"code\":\"SOURCE_UNAVAILABLE\"}' };",
  "  }",
  "  if (!timingSafeEqual(headers['x-nullnull-proxy-token'], proxyToken)) {",
  "    return { statusCode: 403, body: '{\"code\":\"FORBIDDEN\"}' };",
  "  }",
  "  const match = /^\\/citydata\\/([^/]{1,300})$/.exec(path);",
  "  if (!match) return { statusCode: 404, body: '{\"code\":\"NOT_FOUND\"}' };",
  "  const area = decodeURIComponent(match[1]);",
  "  if (!area.trim() || area.length > 100) return { statusCode: 404, body: '{\"code\":\"NOT_FOUND\"}' };",
  "  const upstream = 'http://openapi.seoul.go.kr:8088/' + encodeURIComponent(apiKey)",
  "    + '/json/citydata/1/5/' + encodeURIComponent(area);",
  "  try {",
  // redirect 'error' and a byte ceiling: ENVIRONMENT.md says the provider transport follows no
  // redirect and reads a bounded stream. The Java client does both on ITS hop; without these two
  // this hop did neither, so the guarantee had a hole exactly where the credential is.
  "    const response = await fetch(upstream,",
  "      { redirect: 'error', signal: AbortSignal.timeout(8000) });",
  "    const reader = response.body.getReader();",
  "    const chunks = []; let size = 0;",
  "    for (;;) {",
  "      const { done, value } = await reader.read();",
  "      if (done) break;",
  "      size += value.length;",
  "      if (size > MAX_BYTES) { await reader.cancel(); throw new Error('response_too_large'); }",
  "      chunks.push(value);",
  "    }",
  "    const body = Buffer.concat(chunks).toString('utf8');",
  "    return { statusCode: response.status, headers: { 'content-type': 'application/json' }, body };",
  "  } catch (failure) {",
  // The name only. failure.message can contain the URL, and the URL contains the key.
  "    console.error('seoul_proxy_upstream_failed name=' + (failure && failure.name));",
  "    return { statusCode: 502, body: '{\"code\":\"BAD_GATEWAY\"}' };",
  "  }",
  "};",
].join("\n");

export const GATE_FUNCTION_CODE = [
  "var crypto = require('crypto');",
  "var OPEN = ${Open};",
  "var VERIFIER = '${Verifier}';",
  "function closed() {",
  "  return { statusCode: 503, statusDescription: 'Service Unavailable',",
  "    headers: { 'content-type': { value: 'application/problem+json' }, 'cache-control': { value: 'no-store' } },",
  "    body: '{\"type\":\"about:blank\",\"title\":\"Staging verification pending\",\"status\":503}' };",
  "}",
  "function handler(event) {",
  "  var request = event.request;",
  "  var presented = request.headers['x-nullnull-verifier'];",
  "  delete request.headers['x-nullnull-verifier'];",
  "  if (OPEN) return request;",
  "  if (VERIFIER.length === 64 && presented && presented.value &&",
  "      crypto.createHash('sha256').update(presented.value).digest('hex') === VERIFIER) return request;",
  "  return closed();",
  "}",
].join("\n");
export function createStacks(
  app: cdk.App,
  config: {
    account: string;
    release: Release;
    webDirectory: string;
    // #183 cover photos, staged by staging_operator.py plan from docs/contest/covers (jpg only).
    coversDirectory: string;
    bootstrapOnly?: boolean;
  },
): Record<string, cdk.Stack> {
  const region = "ap-northeast-2";
  // Explicitly pin the approved two AZs so offline synth never requests account lookups.
  app.node.setContext(`availability-zones:account=${config.account}:region=${region}`, [`${region}a`, `${region}c`]);
  const stack = (name: string, global = false) => {
    const s = new cdk.Stack(app, `NullnullStg${name}`, {
      env: { account: config.account, region: global ? "us-east-1" : region },
      terminationProtection: ["Foundation", "Data"].includes(name),
    });
    for (const [k, v] of Object.entries({
      Project: "Nullnull",
      Environment: "staging",
      ManagedBy: "CDK",
      // Stays at the original date on purpose (A-069): changing it retags 86 resources, RDS, Secrets, S3,
      // DynamoDB and the VPC among them, and staging_operator.py refuses that as a stateful change.
      // Nothing reads this tag; the service end is staging_operator.EXPIRY.
      Expiry: "2026-10-25",
    }))
      cdk.Tags.of(s).add(k, v);
    return s;
  };
  const out = (s: cdk.Stack, k: string, v: string) =>
    new cdk.CfnOutput(s, k, { value: v });
  const bucket = (s: cdk.Stack, k: string) =>
    new s3.Bucket(s, k, {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: true,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
  // Every role this app creates carries this boundary, and the CloudFormation execution policy refuses to
  // create or widen a role without it (infra/iam). A template can therefore never mint a role stronger
  // than the boundary, whatever the commit that produced it says.
  const foundation = stack("Foundation");
  iam.PermissionsBoundary.of(app).apply(
    iam.ManagedPolicy.fromManagedPolicyArn(foundation, "RoleBoundary",
      `arn:aws:iam::${config.account}:policy/${ROLE_BOUNDARY_POLICY}`),
  );
  const repo = (k: string, name: string) =>
    new ecr.Repository(foundation, k, {
      repositoryName: name,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      imageScanOnPush: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
  const apiRepo = repo("ApiRepository", "nullnull-stg-api"),
    aiRepo = repo("AiRepository", "nullnull-stg-ai");
  const releases = bucket(foundation, "Releases"),
    ledger = bucket(foundation, "DeletionLedger");
  const lock = new ddb.Table(foundation, "Lock", {
    tableName: "nullnull-stg-deployment-lock",
    partitionKey: { name: "LockId", type: ddb.AttributeType.STRING },
    billingMode: ddb.BillingMode.PAY_PER_REQUEST,
    deletionProtection: true,
    pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
    removalPolicy: cdk.RemovalPolicy.RETAIN,
  });
  // External provider key: created here (before any runtime stack) so the operator can put the real
  // value right after bootstrap. The generated initial value is a placeholder and never a usable key;
  // execution refuses to start Services until the secret has a second version (see staging_operator.py).
  const kto = new sm.Secret(foundation, "Kto", {
    secretName: "nullnull-stg/kto-service-key",
    description: "KTO data.go.kr decoding key. Value is put by the operator, never by CDK.",
  });
  kto.applyRemovalPolicy(cdk.RemovalPolicy.RETAIN);
  // Two values in one secret because they belong to one hop: the operator puts the Seoul key, and the
  // generated proxyToken is what the API task presents to the proxy. apiKey starts empty for the same
  // reason the KTO secret has no generated value - a placeholder that looked like a key would be
  // indistinguishable from a real one that stopped working.
  const seoul = new sm.Secret(foundation, "Seoul", {
    secretName: "nullnull-stg/seoul-proxy",
    description: "Seoul open API key (operator) and the shared proxy token (generated).",
    generateSecretString: {
      secretStringTemplate: JSON.stringify({ apiKey: "" }),
      generateStringKey: "proxyToken",
      passwordLength: 64,
      excludePunctuation: true,
    },
  });
  seoul.applyRemovalPolicy(cdk.RemovalPolicy.RETAIN);
  // Verifier token for the closed API edge: born here, never on disk. The operator and the GitHub
  // `staging` environment secret read it; only its SHA-256 reaches CloudFormation (WebEdge parameter).
  new sm.Secret(foundation, "VerifierToken", {
    secretName: "nullnull-stg/verifier-token",
    generateSecretString: { passwordLength: 64, excludePunctuation: true },
  }).applyRemovalPolicy(cdk.RemovalPolicy.RETAIN);
  // The account already has this provider (another project's role trusts it, 2026-05-17). IAM allows one
  // provider per URL, so it is referenced, never created, changed or deleted by this stack.
  const provider = iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(
    foundation,
    "GitHub",
    `arn:aws:iam::${config.account}:oidc-provider/${githubOidc.provider}`,
  );
  const github = (id: string, roleName: string, subjects: string[]) =>
    new iam.Role(foundation, id, {
      roleName,
      maxSessionDuration: cdk.Duration.hours(2),
      assumedBy: new iam.FederatedPrincipal(
        provider.openIdConnectProviderArn,
        {
          StringEquals: {
            [`${githubOidc.provider}:aud`]: githubOidc.audience,
            // Exact subjects only (StringEquals, any-of). Environment subjects inherit GitHub's
            // deployment-branch policy (main), so PRs, forks and other refs cannot present them.
            [`${githubOidc.provider}:sub`]: subjects.length === 1 ? subjects[0] : subjects,
          },
        },
        "sts:AssumeRoleWithWebIdentity",
      ),
    });
  const acct = config.account;
  // Build job: pushes the two release images and nothing else. No delete, no mutability change.
  const publisher = github("PublishRole", "nullnull-stg-github-publish", githubOidc.publishSubjects);
  publisher.addToPolicy(new iam.PolicyStatement({ actions: ["ecr:GetAuthorizationToken"], resources: ["*"] }));
  publisher.addToPolicy(
    new iam.PolicyStatement({
      actions: [
        "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:DescribeImages",
        "ecr:GetDownloadUrlForLayer",
      ],
      resources: [apiRepo.repositoryArn, aiRepo.repositoryArn],
    }),
  );
  // Plan/deploy jobs (`staging` and `staging-infra` environments). Everything the operator does with its
  // own credentials; CloudFormation changes go through the CDK bootstrap roles only.
  const role = github("DeployRole", "nullnull-stg-github-deploy", githubOidc.deploySubjects);
  const statements: [string[], string[], Record<string, Record<string, unknown>>?][] = [
    // Deploy and asset publishing only. The lookup role carries ReadOnlyAccess over the whole account
    // (other projects' objects included) and nothing here synthesizes with lookups.
    [["sts:AssumeRole"], [region, "us-east-1"].flatMap((r) =>
      ["deploy", "file-publishing"].map(
        (k) => `arn:aws:iam::${acct}:role/cdk-${BOOTSTRAP_QUALIFIER}-${k}-role-${acct}-${r}`))],
    [["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem"], [lock.tableArn]],
    [["cloudformation:DescribeStacks", "cloudformation:GetTemplate", "cloudformation:DescribeStackEvents"], [
      `arn:aws:cloudformation:${region}:${acct}:stack/NullnullStg*/*`,
      `arn:aws:cloudformation:us-east-1:${acct}:stack/NullnullStgGlobalWaf/*`]],
    [["ecs:RunTask"], [`arn:aws:ecs:${region}:${acct}:task-definition/nullnull-stg-migration:*`],
      { ArnEquals: { "ecs:cluster": `arn:aws:ecs:${region}:${acct}:cluster/nullnull-stg` } }],
    [["ecs:DescribeTasks"], [`arn:aws:ecs:${region}:${acct}:task/nullnull-stg/*`]],
    // DescribeTaskDefinition and DescribeLoadBalancers have no resource-level permissions.
    [["ecs:DescribeTaskDefinition", "elasticloadbalancing:DescribeLoadBalancers"], ["*"]],
    [["iam:PassRole"], [`arn:aws:iam::${acct}:role/NullnullStgMigration-MigrationTask*`],
      { StringEquals: { "iam:PassedToService": "ecs-tasks.amazonaws.com" } }],
    [["ecr:DescribeImages"], [apiRepo.repositoryArn, aiRepo.repositoryArn]],
    [["s3:GetObject", "s3:PutObject"], [releases.arnForObjects("*")]],
    [["s3:ListBucket"], [releases.bucketArn]],
    [["secretsmanager:DescribeSecret"], [kto.secretArn]],
    [["logs:GetLogEvents"], [`arn:aws:logs:${region}:${acct}:log-group:NullnullStgPlatform-MigrationLogs*:*`]],
    [["s3:GetBucketPublicAccessBlock", "s3:GetBucketPolicyStatus"], ["arn:aws:s3:::nullnullstgwebedge-*"]],
    [["rds:DescribeDBInstances"], [`arn:aws:rds:${region}:${acct}:db:*`]],
    [["iam:GetRole"], [`arn:aws:iam::${acct}:role/nullnull-stg-github-*`]],
  ];
  for (const [actions, resources, conditions] of statements)
    role.addToPolicy(new iam.PolicyStatement({ actions, resources, conditions }));
  for (const [k, v] of Object.entries({
    DeployRoleName: role.roleName,
    PublishRoleName: publisher.roleName,
    ReleaseBucketName: releases.bucketName,
    DeletionLedgerBucketName: ledger.bucketName,
    DeploymentLockTableName: lock.tableName,
    ApiRepositoryUri: apiRepo.repositoryUri,
    AiRepositoryUri: aiRepo.repositoryUri,
    KtoSecretArn: kto.secretArn,
  }))
    out(foundation, k, v);
  if (config.bootstrapOnly) return { foundation };
  const network = stack("Network");
  const vpc = new ec2.Vpc(network, "Vpc", {
    availabilityZones: [`${region}a`, `${region}c`],
    natGateways: 0,
    subnetConfiguration: [
      {
        name: "egress-public",
        subnetType: ec2.SubnetType.PUBLIC,
        cidrMask: 24,
      },
      {
        name: "edge-private",
        subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
        cidrMask: 24,
      },
      {
        name: "data-isolated",
        subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
        cidrMask: 24,
      },
    ],
  });
  const sg = (name: string) =>
    new ec2.SecurityGroup(network, name, { vpc, allowAllOutbound: false });
  const albSg = sg("AlbSg"),
    apiSg = sg("ApiSg"),
    aiSg = sg("AiSg"),
    dbSg = sg("DatabaseSg"),
    migrationSg = sg("MigrationSg"),
    verifierSg = sg("VerifierSg"),
    restoreSg = sg("RestoreSg");
  const rule = (
    from: ec2.ISecurityGroup,
    to: ec2.ISecurityGroup,
    port: number,
  ) => {
    to.addIngressRule(from, ec2.Port.tcp(port));
    from.addEgressRule(to, ec2.Port.tcp(port));
  };
  // CloudFront VPC origin -> internal ALB. Without this the ALB SG has no ingress at all and every
  // /api/* request times out at the edge (AWS docs, "Restrict access with VPC origins").
  albSg.addIngressRule(
    ec2.Peer.prefixList(CLOUDFRONT_ORIGIN_FACING_PREFIX_LIST),
    ec2.Port.tcp(80),
    "CloudFront VPC origin (origin-facing prefix list)",
  );
  rule(albSg, apiSg, 8080);
  rule(apiSg, aiSg, 8090);
  rule(apiSg, dbSg, 5432);
  rule(migrationSg, dbSg, 5432);
  rule(verifierSg, dbSg, 5432);
  rule(verifierSg, apiSg, 8080);
  rule(verifierSg, restoreSg, 5432);
  for (const g of [apiSg, aiSg, migrationSg, verifierSg]) {
    g.addEgressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(443));
    g.addEgressRule(ec2.Peer.ipv4(vpc.vpcCidrBlock), ec2.Port.udp(53));
    g.addEgressRule(ec2.Peer.ipv4(vpc.vpcCidrBlock), ec2.Port.tcp(53));
  }
  out(network, "VpcId", vpc.vpcId);
  out(network, "RestoreSecurityGroupId", restoreSg.securityGroupId);
  const data = stack("Data");
  const db = new rds.DatabaseInstance(data, "Database", {
    engine: rds.DatabaseInstanceEngine.postgres({
      version: rds.PostgresEngineVersion.VER_17,
    }),
    instanceType: ec2.InstanceType.of(
      ec2.InstanceClass.T4G,
      ec2.InstanceSize.MICRO,
    ),
    vpc,
    vpcSubnets: { subnetGroupName: "data-isolated" },
    securityGroups: [dbSg],
    databaseName: "nullnull",
    // Fixed secret names (nullnull-stg/*) let IAM scope secret access to this project only.
    credentials: rds.Credentials.fromGeneratedSecret("nullnull_admin", {
      secretName: "nullnull-stg/db-admin",
    }),
    multiAz: true,
    publiclyAccessible: false,
    storageEncrypted: true,
    // Storage autoscaling stays off by omitting maxAllocatedStorage: RDS requires that ceiling to exceed
    // allocatedStorage, so setting it equal would fail the instance create.
    allocatedStorage: 20,
    storageType: rds.StorageType.GP3,
    backupRetention: cdk.Duration.days(14),
    deleteAutomatedBackups: false,
    deletionProtection: true,
    removalPolicy: cdk.RemovalPolicy.SNAPSHOT,
    autoMinorVersionUpgrade: false,
    copyTagsToSnapshot: true,
    parameters: { "rds.force_ssl": "1" },
  });
  const appDb = new sm.Secret(data, "ApplicationDatabaseCredentials", {
    secretName: "nullnull-stg/db-app",
    generateSecretString: {
      secretStringTemplate: JSON.stringify({ username: "nullnull_app" }),
      generateStringKey: "password",
      passwordLength: 48,
      excludePunctuation: true,
    },
  });
  appDb.applyRemovalPolicy(cdk.RemovalPolicy.RETAIN);
  const cursor = new sm.Secret(data, "Cursor", {
      secretName: "nullnull-stg/cursor-secret",
      generateSecretString: { passwordLength: 64, excludePunctuation: true },
    }),
    deletion = new sm.Secret(data, "Deletion", {
      secretName: "nullnull-stg/deletion-token-secret",
      generateSecretString: { passwordLength: 64, excludePunctuation: true },
    });
  for (const s of [cursor, deletion])
    s.applyRemovalPolicy(cdk.RemovalPolicy.RETAIN);
  out(data, "DatabaseIdentifier", db.instanceIdentifier);
  out(data, "DatabaseSecretArn", db.secret!.secretArn);
  out(data, "DatabaseSecurityGroupId", dbSg.securityGroupId);
  out(
    data,
    "DatabaseSubnetGroupName",
    (db.node.defaultChild as rds.CfnDBInstance).dbSubnetGroupName!,
  );
  const platform = stack("Platform");
  const cluster = new ecs.Cluster(platform, "Cluster", {
    vpc,
    clusterName: "nullnull-stg",
    defaultCloudMapNamespace: { name: "nullnull.internal" },
  });
  const alb = new elb.ApplicationLoadBalancer(platform, "InternalAlb", {
    vpc,
    internetFacing: false,
    vpcSubnets: { subnetGroupName: "edge-private" },
    securityGroup: albSg,
    dropInvalidHeaderFields: true,
  });
  const targets = new elb.ApplicationTargetGroup(platform, "ApiTargets", {
    vpc,
    port: 8080,
    protocol: elb.ApplicationProtocol.HTTP,
    targetType: elb.TargetType.IP,
    healthCheck: { path: "/api/v1/health/ready", healthyHttpCodes: "200" },
  });
  alb.addListener("Http", {
    port: 80,
    open: false,
    defaultTargetGroups: [targets],
  });
  const log = (name: string) =>
    new logs.LogGroup(platform, name, {
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
  const apiLogs = log("ApiLogs"),
    aiLogs = log("AiLogs"),
    migrationLogs = log("MigrationLogs");
  const runtimePlatform = {
    cpuArchitecture: ecs.CpuArchitecture.X86_64,
    operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
  };
  // Fargate creates a bind-mount volume as root:root 0755 (ECS "bind mounts" considerations), and the API image
  // runs as its non-root user on a read-only root, so /tmp was unwritable: Tomcat "Unable to create tempDir"
  // stopped the first Services deploy (2026-09-19). A short root container opens the empty volume first.
  const writableTmp = (
    task: ecs.FargateTaskDefinition,
    app: ecs.ContainerDefinition,
    logGroup: logs.ILogGroup,
  ) => {
    const init = task.addContainer("tmp-permissions", {
      image: ecs.ContainerImage.fromEcrRepository(apiRepo, config.release.apiImageDigest),
      essential: false,
      user: "0",
      readonlyRootFilesystem: true,
      entryPoint: ["sh", "-c"],
      command: ["chmod 1777 /tmp"],
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: "tmp-permissions", logGroup }),
    });
    init.addMountPoints({ containerPath: "/tmp", sourceVolume: "tmp", readOnly: false });
    app.addContainerDependencies({ container: init, condition: ecs.ContainerDependencyCondition.SUCCESS });
  };
  const dbEnv = {
    SPRING_DATASOURCE_URL: `jdbc:postgresql://${db.dbInstanceEndpointAddress}:5432/nullnull?sslmode=require`,
    SPRING_PROFILES_ACTIVE: "staging",
    NULLNULL_ENV: "staging",
  };
  const dbSecrets = {
    SPRING_DATASOURCE_USERNAME: ecs.Secret.fromSecretsManager(
      db.secret!,
      "username",
    ),
    SPRING_DATASOURCE_PASSWORD: ecs.Secret.fromSecretsManager(
      db.secret!,
      "password",
    ),
  };
  for (const [k, v] of Object.entries({
    ClusterName: cluster.clusterName,
    AppSubnetIds: vpc.publicSubnets.map((s) => s.subnetId).join(","),
    ApiSecurityGroupId: apiSg.securityGroupId,
    MigrationSecurityGroupId: migrationSg.securityGroupId,
    VerifierSecurityGroupId: verifierSg.securityGroupId,
    InternalAlbArn: alb.loadBalancerArn,
    ApiTargetGroupArn: targets.targetGroupArn,
    MigrationLogGroupName: migrationLogs.logGroupName,
  }))
    out(platform, k, v);
  // Everything that pins a release digest lives outside the protected stacks. The migration task used
  // to sit in Platform, so every app-only release rewrote a protected template and a rollback (which
  // never redeploys Platform) left migration on the newer digest than Services.
  const migrationStack = stack("Migration");
  const migration = new ecs.FargateTaskDefinition(migrationStack, "MigrationTask", {
    family: "nullnull-stg-migration",
    cpu: 512,
    memoryLimitMiB: 1024,
    runtimePlatform,
  });
  migration.addContainer("migration", {
    image: ecs.ContainerImage.fromEcrRepository(
      apiRepo,
      config.release.apiImageDigest,
    ),
    readonlyRootFilesystem: true,
    command: ["--nullnull.migration-only=true"],
    environment: {
      ...dbEnv,
      SPRING_FLYWAY_ENABLED: "true",
      NULLNULL_JOBS_ENABLED: "false",
    },
    secrets: {
      ...dbSecrets,
      NULLNULL_APP_DB_USERNAME: ecs.Secret.fromSecretsManager(
        appDb,
        "username",
      ),
      NULLNULL_APP_DB_PASSWORD: ecs.Secret.fromSecretsManager(
        appDb,
        "password",
      ),
    },
    logging: ecs.LogDrivers.awsLogs({
      streamPrefix: "migration",
      logGroup: migrationLogs,
    }),
  });
  // Operator one-off commands (KTO smoke/ingest, curated imports) run the SAME API image through Spring
  // Boot's PropertiesLauncher; the main class is chosen per run with LOADER_MAIN from an allowlist in
  // staging_operator.py. ECS overrides cannot change the entry point, hence a separate definition. It is
  // runnable only by the operator role (the GitHub deploy role may run the migration family only), has no
  // job workers, no Flyway and no web server, and KTO actual calls still require the approval variable
  // that KtoSmokeMain reads from the process environment.
  const ops = new ecs.FargateTaskDefinition(migrationStack, "OpsTask", {
    family: "nullnull-stg-ops",
    cpu: 512,
    memoryLimitMiB: 1024,
    runtimePlatform,
  });
  ops.addVolume({ name: "tmp" });
  const opsContainer = ops.addContainer("ops", {
    image: ecs.ContainerImage.fromEcrRepository(apiRepo, config.release.apiImageDigest),
    readonlyRootFilesystem: true,
    entryPoint: [
      "java", "-XX:MaxRAMPercentage=75", "-cp", "/app/nullnull-api.jar",
      "org.springframework.boot.loader.launch.PropertiesLauncher",
    ],
    environment: {
      ...dbEnv,
      SPRING_FLYWAY_ENABLED: "false",
      NULLNULL_JOBS_ENABLED: "false",
      NULLNULL_AI_BASE_URL: "http://ai.nullnull.internal:8090",
      KTO_BASE_URL: "https://apis.data.go.kr/B551011/KorService2",
      KTO_FORECAST_BASE_URL: "https://apis.data.go.kr/B551011/TatsCnctrRateService",
      // BA-086 (#60): only the English text refresh reads it; the same host as the two above.
      KTO_ENG_BASE_URL: "https://apis.data.go.kr/B551011/EngService2",
      APP_RELEASE_VERSION: config.release.releaseVersion,
    },
    secrets: {
      SPRING_DATASOURCE_USERNAME: ecs.Secret.fromSecretsManager(appDb, "username"),
      SPRING_DATASOURCE_PASSWORD: ecs.Secret.fromSecretsManager(appDb, "password"),
      NULLNULL_CURSOR_SECRET: ecs.Secret.fromSecretsManager(cursor),
      NULLNULL_DELETION_TOKEN_SECRET: ecs.Secret.fromSecretsManager(deletion),
      KTO_SERVICE_KEY: ecs.Secret.fromSecretsManager(kto),
      SEOUL_PROXY_TOKEN: ecs.Secret.fromSecretsManager(seoul, "proxyToken"),
    },
    logging: ecs.LogDrivers.awsLogs({ streamPrefix: "ops", logGroup: migrationLogs }),
  });
  opsContainer.addMountPoints({ containerPath: "/tmp", sourceVolume: "tmp", readOnly: false });
  writableTmp(ops, opsContainer, migrationLogs);
  for (const [k, v] of Object.entries({
    MigrationTaskDefinitionArn: migration.taskDefinitionArn,
    MigrationContainerName: "migration",
    OpsTaskDefinitionArn: ops.taskDefinitionArn,
    OpsContainerName: "ops",
  }))
    out(migrationStack, k, v);
  // A-044 (owner decision, 2026-09-19). The demo forecast is re-read on a schedule, so the KTO approval
  // variable in the overrides below is STANDING: no person approves the 2 calls this makes every 12 h
  // (4 a day) until judging ends. staging_operator.py demands both the caller's own approval variable and
  // a --owner-approval record ("Neither alone runs"), and a schedule is nobody's caller; the owner
  // approved the standing form once instead, and it is written here in the open rather than hidden in a
  // task definition, where every principal able to RunTask that family would inherit it.
  //
  // It runs the operator's own ops definition, image and revision, but NOT through staging_operator.py:
  // ops tasks are local-only there (`ops-tasks-are-local-only`), so this is a second path to the same
  // task. What that path does not carry - the deployment lock and the operator's log allowlist - and what
  // replaces the rest, is the table in docs/operations/STAGING_DEPLOYMENT_RUNBOOK.md. Release binding is
  // the one that matters and it is kept here: this schedule lives in Migration, an app stack, so its
  // taskDefinition below is re-pointed at the new revision by the same deploy that publishes it.
  //
  // The target is universal, not the templated ECS one: a templated target carries EcsParameters only and
  // cannot carry container overrides, and without an override there is no LOADER_MAIN, hence no main
  // class. The input is therefore the ECS RunTask request itself, in the PascalCase member names Scheduler
  // validates universal-target input against - not the camelCase of the ECS JSON API or boto3. The first
  // deploy with camelCase keys was refused at CREATE ("Request payload is missing the following field(s):
  // TaskDefinition", run 35457509371), and a synth-only test cannot see that refusal.
  const operationsTarget = `postgresql://${db.dbInstanceEndpointAddress}:5432/nullnull`;
  // One shape, two clocks. Each mode renews one of them before it lapses (KtoDemoRefresh), and neither
  // renews the other's, so a single schedule would leave the forecast without a mapping to ask with.
  const demoRefresh = (
    id: string,
    name: string,
    main: string,
    approval: string,
    rate: cdk.Duration,
    description: string,
  ) =>
    new scheduler.Schedule(migrationStack, id, {
      // Named, like the cluster and the task families, so the runbook and the IAM policy can both say
      // which schedule they mean instead of a generated suffix.
      scheduleName: name,
      schedule: scheduler.ScheduleExpression.rate(rate),
      // The schedule stops itself at the judging expiry. A task-side check would still need something to
      // stop calling KTO; this stops the calling.
      end: FORECAST_SCHEDULE_END,
      timeWindow: scheduler.TimeWindow.off(),
      description,
      target: new schedulerTargets.Universal({
        service: "ecs",
        action: "runTask",
        // An invocation that cannot start within the hour waits for the next tick instead of piling onto
        // it; the missing-success alarm is what reports the gap, not a longer retry queue.
        maxEventAge: cdk.Duration.hours(1),
        retryAttempts: 3,
        input: scheduler.ScheduleTargetInput.fromObject({
          Cluster: cluster.clusterArn,
          TaskDefinition: ops.taskDefinitionArn,
          LaunchType: "FARGATE",
          Count: 1,
          // Distinct from the operator's own 'nullnull-stg-ops', and per mode, so a running task says
          // which of the two started it (RunTask caps this at 36 characters).
          StartedBy: name,
          NetworkConfiguration: {
            AwsvpcConfiguration: {
              Subnets: vpc.publicSubnets.map((s) => s.subnetId),
              SecurityGroups: [migrationSg.securityGroupId],
              AssignPublicIp: "ENABLED",
            },
          },
          Overrides: {
            ContainerOverrides: [
              {
                Name: "ops",
                Environment: [
                  { Name: "LOADER_MAIN", Value: main },
                  { Name: "NULLNULL_DEMO_PLACES", Value: FORECAST_DEMO_PLACES },
                  { Name: "APP_CONTEST_PROFILE", Value: "2026_KTO_WEBAPP" },
                  { Name: approval, Value: "true" },
                  // OperationsContext.target() of the task's own spring.datasource.url, which is dbEnv's
                  // JDBC URL above: same endpoint, same database, so a task that reached another database
                  // refuses itself before it connects.
                  { Name: "NULLNULL_OPERATIONS_TARGET", Value: operationsTarget },
                ],
              },
            ],
          },
        }),
        // The role is created inside the app's permissions boundary, which already limits ecs:RunTask to
        // nullnull-stg-* definitions and iam:PassRole to ecs-tasks.amazonaws.com. These statements narrow
        // it further to this one family and this cluster.
        policyStatements: [
          new iam.PolicyStatement({
            actions: ["ecs:RunTask"],
            resources: [
              `arn:aws:ecs:${region}:${config.account}:task-definition/nullnull-stg-ops:*`,
            ],
            conditions: { ArnEquals: { "ecs:cluster": cluster.clusterArn } },
          }),
          new iam.PolicyStatement({
            actions: ["iam:PassRole"],
            resources: [ops.taskRole.roleArn, ops.executionRole!.roleArn],
            conditions: {
              StringEquals: {
                "iam:PassedToService": "ecs-tasks.amazonaws.com",
              },
            },
          }),
        ],
      }),
    });
  const forecastSchedule = demoRefresh(
    "ForecastRefresh",
    "nullnull-stg-forecast-refresh",
    FORECAST_MAIN,
    "NULLNULL_KTO_FORECAST_SMOKE_APPROVED",
    cdk.Duration.hours(FORECAST_SCHEDULE_RATE_HOURS),
    "KTO demo forecast refresh every 12h (A-044 standing approval), until the judging expiry",
  );
  // The clock under the forecast. It renews the detail snapshot a forecast request is built from, and
  // its approval variable is the one kto-smoke also uses - standing here, and only here: it rides in
  // this schedule's input, never in the task definition, so the operator's own gate is untouched.
  const detailSchedule = demoRefresh(
    "DetailRefresh",
    "nullnull-stg-detail-refresh",
    DETAIL_MAIN,
    "NULLNULL_KTO_SMOKE_APPROVED",
    cdk.Duration.days(DETAIL_SCHEDULE_RATE_DAYS),
    "KTO demo detail refresh every 5 days (A-044 standing approval), until the judging expiry",
  );
  for (const [k, v] of Object.entries({
    ForecastScheduleName: forecastSchedule.scheduleName,
    DetailScheduleName: detailSchedule.scheduleName,
  }))
    out(migrationStack, k, v);
  const globalWaf = stack("GlobalWaf", true);
  const visibility = {
    cloudWatchMetricsEnabled: true,
    sampledRequestsEnabled: false,
    metricName: "nullnull-stg-waf",
  };
  const acl = new waf.CfnWebACL(globalWaf, "WebAcl", {
    scope: "CLOUDFRONT",
    defaultAction: { allow: {} },
    visibilityConfig: visibility,
    rules: [
      {
        name: "ApiRate",
        priority: 0,
        action: { block: {} },
        visibilityConfig: { ...visibility, metricName: "api-rate" },
        statement: {
          rateBasedStatement: {
            limit: 2000,
            aggregateKeyType: "IP",
            scopeDownStatement: {
              byteMatchStatement: {
                searchString: "/api/",
                fieldToMatch: { uriPath: {} },
                positionalConstraint: "STARTS_WITH",
                textTransformations: [{ priority: 0, type: "NONE" }],
              },
            },
          },
        },
      },
      {
        name: "CommonCount",
        priority: 1,
        overrideAction: { count: {} },
        visibilityConfig: { ...visibility, metricName: "common-count" },
        statement: {
          managedRuleGroupStatement: {
            vendorName: "AWS",
            name: "AWSManagedRulesCommonRuleSet",
          },
        },
      },
    ],
  });
  out(globalWaf, "WebAclArn", acl.attrArn);
  const web = stack("WebEdge");
  const wafArn = new cdk.CfnParameter(web, "GlobalWebAclArn", {
    type: "String",
    allowedPattern: "arn:aws:wafv2:us-east-1:[0-9]{12}:global/webacl/.+",
  });
  const traffic = new cdk.CfnParameter(web, "TrafficEnabled", {
    type: "String",
    default: "false",
    allowedValues: ["false", "true"],
  });
  const enabled = new cdk.CfnCondition(web, "TrafficOpen", {
    expression: cdk.Fn.conditionEquals(traffic.valueAsString, "true"),
  });
  // SHA-256 (hex) of a 256-bit verifier token, or "" for no verifier. Only the hash is in CloudFormation;
  // the token lives in the owner's ignored local file and the GitHub `staging` environment secret.
  const verifier = new cdk.CfnParameter(web, "VerifierTokenSha256", {
    type: "String",
    default: "",
    allowedPattern: "^([a-f0-9]{64})?$",
  });
  const webBucket = bucket(web, "WebBucket");
  // Presigned quarantine uploads come directly from this browser origin to S3.
  // A literal avoids a bucket -> distribution -> bucket dependency cycle.
  webBucket.addCorsRule({
    allowedOrigins: ["https://d54awmnmi4c3z.cloudfront.net"],
    allowedMethods: [s3.HttpMethods.PUT],
    allowedHeaders: ["content-type"],
    maxAge: 300,
  });
  const spa = new cf.Function(web, "SpaRewrite", {
    functionName: "nullnull-stg-spa-rewrite",
    code: cf.FunctionCode.fromInline(
      // /quarantine is refused for the same reason /api is: the bucket behind this behaviour holds
      // BA-082 uploads before they have been validated, and A-058 makes automatic validation the
      // only boundary in front of a published image. Without this line the rewrite below would have
      // hidden them by accident (a key with no dot becomes /index.html), which is not the same as
      // refusing them - a quarantined .jpg WOULD have been served.
      "function handler(e){var r=e.request;if(r.uri.indexOf('/api')===0)return {statusCode:404};if(r.uri.indexOf('/quarantine')===0)return {statusCode:404};if(r.uri.indexOf('.')===-1)r.uri='/index.html';return r;}",
    ),
  });
  const gate = new cf.CfnFunction(web, "ApiGate", {
    name: "nullnull-stg-api-gate",
    autoPublish: true,
    functionConfig: {
      comment: "Closed until safety verification; verifier token passes",
      runtime: "cloudfront-js-2.0",
    },
    // The verifier header is removed before the request leaves the edge, open or closed, so the token
    // never reaches the ALB, the API or its access log. No "${" may appear in this code (Fn::Sub).
    functionCode: cdk.Fn.sub(GATE_FUNCTION_CODE, {
      Open: cdk.Fn.conditionIf(enabled.logicalId, "true", "false").toString(),
      Verifier: verifier.valueAsString,
    }),
  });
  const dist = new cf.Distribution(web, "Distribution", {
    defaultRootObject: "index.html",
    webAclId: wafArn.valueAsString,
    minimumProtocolVersion: cf.SecurityPolicyProtocol.TLS_V1_2_2021,
    defaultBehavior: {
      origin: origins.S3BucketOrigin.withOriginAccessControl(webBucket),
      viewerProtocolPolicy: cf.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
      cachePolicy: cf.CachePolicy.CACHING_DISABLED,
      // HSTS, nosniff, frame-options and referrer-policy for the web bundle (the API sets its own).
      responseHeadersPolicy: cf.ResponseHeadersPolicy.SECURITY_HEADERS,
      functionAssociations: [
        { eventType: cf.FunctionEventType.VIEWER_REQUEST, function: spa },
      ],
    },
    additionalBehaviors: {
      "/api/*": {
        // The ALB listens on HTTP:80 only. Without an explicit policy CloudFormation defaults the VPC
        // origin to match-viewer, i.e. HTTPS:443 for every HTTPS viewer, which the ALB never answers.
        // The hop is inside the VPC (CloudFront service ENI -> internal ALB), not the public internet.
        origin: origins.VpcOrigin.withApplicationLoadBalancer(alb, {
          protocolPolicy: cf.OriginProtocolPolicy.HTTP_ONLY,
          httpPort: 80,
        }),
        viewerProtocolPolicy: cf.ViewerProtocolPolicy.HTTPS_ONLY,
        allowedMethods: cf.AllowedMethods.ALLOW_ALL,
        cachePolicy: cf.CachePolicy.CACHING_DISABLED,
        originRequestPolicy: cf.OriginRequestPolicy.ALL_VIEWER,
        functionAssociations: [
          {
            eventType: cf.FunctionEventType.VIEWER_REQUEST,
            function: cf.Function.fromFunctionAttributes(web, "GateRef", {
              functionArn: gate.attrFunctionArn,
              functionName: gate.name,
            }),
          },
        ],
      },
    },
  });
  // The approved withdraw-post command runs in OpsTask, not the online API task. Give that task
  // only the user-cover version operations needed to remove one exact key after the DB commit.
  opsContainer.addEnvironment("NULLNULL_UPLOAD_S3_BUCKET", webBucket.bucketName);
  opsContainer.addEnvironment("NULLNULL_UPLOAD_S3_REGION", cdk.Stack.of(migrationStack).region);
  opsContainer.addEnvironment("NULLNULL_UPLOAD_S3_PUBLIC_BASE_URL",
    `https://${dist.distributionDomainName}`);
  ops.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
    actions: ["s3:ListBucketVersions"],
    resources: [webBucket.bucketArn],
    conditions: { StringLike: { "s3:prefix": ["covers/user/*"] } },
  }));
  ops.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
    actions: ["s3:DeleteObjectVersion"],
    resources: [webBucket.arnForObjects("covers/user/*")],
  }));
  new deploy.BucketDeployment(web, "WebRelease", {
    destinationBucket: webBucket,
    sources: [deploy.Source.asset(config.webDirectory)],
    prune: false,
    retainOnDelete: true,
    cacheControl: [deploy.CacheControl.noCache()],
    distribution: dist,
    distributionPaths: ["/*"],
  });
  // #183: the curated posts' cover photos, at <PublicUrl>/covers/<file>. They are team-made first-party assets (NOT photographs - see covers/README.md)
  // (A-024), content rather than the app bundle - apps/web/public is pinned to an exact allowlist
  // (image-assets.test.ts) and the web artifact to the release manifest's webArtifactSha256 - so they come from
  // their own directory, which the operator's plan step copies out of the repository. Being an asset of this
  // assembly, they are inside the sha the owner approves for the release, byte for byte, and ops/curated-posts.json
  // names the same bytes by checksum (scripts/tests/test_curated_post_covers.py). media_assets refuses anything but
  // an absolute https URL (V021), which is what this distribution serves; the default behaviour's rewrite
  // leaves a path with a dot alone, so /covers/x.jpg reaches the bucket and not index.html.
  new deploy.BucketDeployment(web, "CuratedCovers", {
    destinationBucket: webBucket,
    destinationKeyPrefix: "covers/",
    sources: [deploy.Source.asset(config.coversDirectory)],
    // Never removes a cover a published post may still point at; a replaced photo is a new file name.
    prune: false,
    retainOnDelete: true,
    cacheControl: [
      deploy.CacheControl.setPublic(),
      deploy.CacheControl.maxAge(cdk.Duration.days(1)),
    ],
    distribution: dist,
    distributionPaths: ["/covers/*"],
  });
  out(web, "PublicUrl", `https://${dist.distributionDomainName}`);
  out(web, "DistributionId", dist.distributionId);
  out(web, "WebBucketName", webBucket.bucketName);
  const services = stack("Services");
  const aiTask = new ecs.FargateTaskDefinition(services, "AiTask", {
    family: "nullnull-stg-ai",
    cpu: 256,
    memoryLimitMiB: 512,
    runtimePlatform,
  });
  const aiContainer = aiTask.addContainer("ai", {
    image: ecs.ContainerImage.fromEcrRepository(
      aiRepo,
      config.release.aiImageDigest,
    ),
    environment: {
      NULLNULL_ENV: "staging",
      NULLNULL_CATALOG_VERSION: config.release.aiCatalogVersion,
    },
    readonlyRootFilesystem: true,
    logging: ecs.LogDrivers.awsLogs({ streamPrefix: "ai", logGroup: aiLogs }),
  });
  aiContainer.addPortMappings({ containerPort: 8090 });
  const aiService = new ecs.FargateService(services, "AiService", {
    cluster,
    taskDefinition: aiTask,
    desiredCount: 1,
    assignPublicIp: true,
    vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    securityGroups: [aiSg],
    cloudMapOptions: { name: "ai" },
    circuitBreaker: { rollback: true },
    minHealthyPercent: 100,
    maxHealthyPercent: 200,
  });
  const apiTask = new ecs.FargateTaskDefinition(services, "ApiTask", {
    family: "nullnull-stg-api",
    cpu: 512,
    memoryLimitMiB: 1024,
    runtimePlatform,
  });
  apiTask.addVolume({ name: "tmp" });
  // Outside the VPC on purpose: this function needs Secrets Manager and the public internet, and
  // nothing else. Inside, it would need a NAT and A-029's cost plan would have to be recalculated.
  // There is no always-on resource here either - A-050 records that this account cannot have budget
  // alarms, so a fixed cost nobody is watching is the thing not to add.
  // THE NAME IS NOT COSMETIC. infra/iam/cfn-execution.json:61 allows the CloudFormation execution
  // role to act on "arn:aws:lambda:*:${Account}:function:NullnullStg*" and role-boundary.json:144
  // allows "log-group:/aws/lambda/NullnullStg*". IAM resource patterns are case-sensitive, so a
  // lowercase nullnull-stg-* function is outside BOTH: the deployment is denied, and a function
  // that somehow deployed could not write the log line that is its only failure signal.
  //
  // AND THE OFFLINE GATE CANNOT SEE THAT. infra_check=pass means the templates synthesised and the
  // assertions held; it does not simulate IAM. A green gate says nothing about whether this name is
  // deployable - that only shows up at deploy time.
  const seoulProxy = new lambda.Function(services, "SeoulProxy", {
    functionName: "NullnullStgSeoulProxy",
    runtime: lambda.Runtime.NODEJS_22_X,
    handler: "index.handler",
    code: lambda.Code.fromInline(SEOUL_PROXY_CODE),
    timeout: cdk.Duration.seconds(15),
    memorySize: 256,
    // The ARN, not the name: across stacks CDK reconstructs a name from the ARN with nested
    // Fn::Split/Fn::Select, and GetSecretValue takes either. The ARN is the unambiguous one.
    environment: { SECRET_ID: seoul.secretArn },
  });
  seoul.grantRead(seoulProxy);
  // authType NONE with a shared token checked inside the function, not AWS_IAM: IAM auth needs the
  // caller to sign with SigV4 and ProviderHttpClient sends a plain GET. The endpoint is not open -
  // the function answers 403 without the token, and it can only ever reach one upstream path.
  const seoulProxyUrl = seoulProxy.addFunctionUrl({
    authType: lambda.FunctionUrlAuthType.NONE,
  });
  // https://<id>.lambda-url.<region>.on.aws/ -> the bare host, which is what the source allowlist takes.
  const seoulProxyHost = cdk.Fn.select(2, cdk.Fn.split("/", seoulProxyUrl.url));
  out(services, "SeoulProxyUrl", seoulProxyUrl.url);
  out(services, "SeoulProxyHost", seoulProxyHost);

  const apiContainer = apiTask.addContainer("api", {
    image: ecs.ContainerImage.fromEcrRepository(
      apiRepo,
      config.release.apiImageDigest,
    ),
    readonlyRootFilesystem: true,
    environment: {
      ...dbEnv,
      SPRING_FLYWAY_ENABLED: "false",
      APP_PUBLIC_ORIGIN: `https://${dist.distributionDomainName}`,
      APP_COOKIE_SECURE: "true",
      APP_RELEASE_VERSION: config.release.releaseVersion,
      NULLNULL_AI_BASE_URL: "http://ai.nullnull.internal:8090",
      KTO_BASE_URL: "https://apis.data.go.kr/B551011/KorService2",
      KTO_FORECAST_BASE_URL:
        "https://apis.data.go.kr/B551011/TatsCnctrRateService",
      APP_CONTEST_PROFILE: "NONE",
      // BA-082 uploads (owner decision (A), 2026-09-20): the bucket the edge already serves, under
      // two prefixes of its own, rather than a new bucket and distribution. The grant below is what
      // keeps that safe - this task can write covers/user/ and nothing else.
      NULLNULL_UPLOAD_S3_BUCKET: webBucket.bucketName,
      NULLNULL_UPLOAD_S3_REGION: cdk.Stack.of(services).region,
      NULLNULL_UPLOAD_S3_PUBLIC_BASE_URL: `https://${dist.distributionDomainName}`,
      // C3 place reads stay 503 until the operator records staging KTO provenance and flips this file.
      NULLNULL_CATALOG_PUBLIC_ENABLED: String(stagingConfig.catalogPublicEnabled),
      // The submission build runs ITEM optimization (owner decision 2026-09-19, docs/operations/ENVIRONMENT.md).
      // A settled product decision rather than an operator gate, so it is fixed here and not in staging.config.json.
      FEATURE_OPTIMIZATION_ITEM: "true",
      // A-054 (owner, 2026-09-20, confirmed in two sessions) supersedes A-033: Live ships with the
      // submission rather than as a mockup. Three things had to stand first and all three do -
      // SEOUL_CITYDATA promoted to DEV_APPROVED in V046 (which derives enabled from approval_state
      // and stale_after_seconds), the proxy URL and token passed below, and a collector that stores a
      // reading per area. An ON flag now has a collector and read path behind it.
      // Fixed here rather than in staging.config.json for the same reason as the line above: this is a
      // settled product decision, not an operator gate.
      FEATURE_LIVE_DATA: "true",
      // The approved-manifest reader is present. Readiness stays UNAVAILABLE until an owner-approved
      // capture exists; then the API may serve it as explicitly labelled REPLAY without redeploying.
      FEATURE_REPLAY_MODE: "true",
      NULLNULL_LIVE_SCHEDULE_ENABLED: "true",
      // The proxy, never openapi.seoul.go.kr. Both values are set together because they are two halves
      // of one fact: if the allowlist still named the provider while the base URL named the proxy, a
      // request built for the proxy - with no key in its path - would go to Seoul instead.
      SEOUL_BASE_URL: seoulProxyUrl.url,
      SEOUL_ALLOWED_HOST: seoulProxyHost,
    },
    secrets: {
      SPRING_DATASOURCE_USERNAME: ecs.Secret.fromSecretsManager(
        appDb,
        "username",
      ),
      SPRING_DATASOURCE_PASSWORD: ecs.Secret.fromSecretsManager(
        appDb,
        "password",
      ),
      NULLNULL_CURSOR_SECRET: ecs.Secret.fromSecretsManager(cursor),
      NULLNULL_DELETION_TOKEN_SECRET: ecs.Secret.fromSecretsManager(deletion),
      KTO_SERVICE_KEY: ecs.Secret.fromSecretsManager(kto),
      // The token only. The Seoul API key lives in the same secret and is NOT handed to this task:
      // the proxy holds it, and a task that never receives a value cannot leak one.
      SEOUL_PROXY_TOKEN: ecs.Secret.fromSecretsManager(seoul, "proxyToken"),
    },
    logging: ecs.LogDrivers.awsLogs({ streamPrefix: "api", logGroup: apiLogs }),
  });
  apiContainer.addMountPoints({
    containerPath: "/tmp",
    sourceVolume: "tmp",
    readOnly: false,
  });
  writableTmp(apiTask, apiContainer, apiLogs);
  apiContainer.addPortMappings({ containerPort: 8080 });
  // BA-082 object access, scoped by prefix rather than by bucket.
  //
  // NOT bucket.grantPut(): that helper also edits the bucket policy, which lives in the WebEdge
  // stack, and a grant that writes into both stacks makes the dependency bidirectional. Services
  // already depends on WebEdge (APP_PUBLIC_ORIGIN reads the distribution domain), so a role-only
  // statement keeps that arrow pointing one way.
  //
  // THE PREFIXES ARE THE POINT. This bucket also holds the application bundle the distribution
  // serves. A task that could PutObject on the bucket could replace index.html, so the write grant
  // names covers/user/ exactly. Quarantine needs put as well as get: the browser's upload is a
  // presigned PUT, and a presigned URL can only carry authority the signer itself has.
  apiTask.taskRole.addToPrincipalPolicy(
    new iam.PolicyStatement({
      actions: ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      resources: [webBucket.arnForObjects("quarantine/*")],
    }),
  );
  apiTask.taskRole.addToPrincipalPolicy(
    new iam.PolicyStatement({
      actions: ["s3:PutObject"],
      resources: [webBucket.arnForObjects("covers/user/*")],
    }),
  );

  const apiService = new ecs.FargateService(services, "ApiService", {
    cluster,
    taskDefinition: apiTask,
    desiredCount: 1,
    assignPublicIp: true,
    vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    securityGroups: [apiSg],
    circuitBreaker: { rollback: true },
    minHealthyPercent: 100,
    maxHealthyPercent: 200,
    // ECS ignores the target's ALB health for this long, so it must cover the slowest path to healthy.
    // The target is registered before the JVM listens: the first checks fail, the target turns unhealthy,
    // and it recovers only after HealthyThresholdCount x interval (target group defaults, 5 x 30 s).
    // Measured on the 2026-09-19 release of 812f2cb (ECS service events and the app log): task start to a
    // listening app took 74 s and the target would have been healthy at about 215 s. At 120 s ECS stopped
    // three tasks shortly before they recovered and the circuit breaker rolled the release back.
    healthCheckGracePeriod: cdk.Duration.seconds(300),
  });
  apiService.attachToApplicationTargetGroup(targets);
  services.addStackDependency(platform);
  services.addStackDependency(web);
  out(services, "ApiServiceName", apiService.serviceName);
  out(services, "AiServiceName", aiService.serviceName);
  out(services, "InternalAlbArn", alb.loadBalancerArn);
  const obs = stack("Observability");
  const topic = new sns.Topic(obs, "Alarms", { enforceSSL: true });
  // enforceSSL writes a topic policy, and a topic policy REPLACES the default one - the default was what let
  // CloudWatch publish. An IAM principal still publishes on its own identity policy (the subscribe script's
  // test mail arrived), but CloudWatch is a service principal with no identity policy here, so every alarm
  // action failed: run of the BA-072-T7 drill, five alarms went OK -> ALARM and each logged "Failed to execute
  // action" on this topic. Only this account's alarms may publish.
  topic.addToResourcePolicy(
    new iam.PolicyStatement({
      sid: "CloudWatchAlarmsPublish",
      principals: [new iam.ServicePrincipal("cloudwatch.amazonaws.com")],
      actions: ["sns:Publish"],
      resources: [topic.topicArn],
      conditions: {
        ArnLike: { "aws:SourceArn": `arn:aws:cloudwatch:${region}:${config.account}:alarm:*` },
        StringEquals: { "aws:SourceAccount": config.account },
      },
    }),
  );
  // Who receives this topic is not decided here. scripts/aws/staging-alarm-subscribe.sh subscribes the
  // primary (and secondary) address from the operator's ignored local settings, checks that the
  // subscription was confirmed, and can publish the BA-072-T3 test; a CloudFormation subscription could
  // do only the first of those, and two owners of one subscription is one owner too many (A-037 keeps
  // the address out of Git, so it could never be a literal here either). Every alarm below therefore
  // publishes to this one topic, and reaching a person is that script's job.
  const alarm = (
    name: string,
    metric: cw.IMetric,
    threshold: number,
    comparisonOperator = cw.ComparisonOperator
      .GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
    // An occurrence alarm ("this bad line appeared") needs one period and must not read an empty window
    // as bad; a continuous signal ("the host count fell") needs two and must read silence as bad.
    options: {
      evaluationPeriods?: number;
      treatMissingData?: cw.TreatMissingData;
    } = {},
  ) => {
    const a = new cw.Alarm(obs, name, {
      metric,
      threshold,
      evaluationPeriods: options.evaluationPeriods ?? 2,
      comparisonOperator,
      treatMissingData:
        options.treatMissingData ?? cw.TreatMissingData.BREACHING,
    });
    a.addAlarmAction(new actions.SnsAction(topic));
    return a;
  };
  // One metric per quoted phrase. defaultValue 0 makes a window with other traffic read as zero rather
  // than as missing, so only true silence is missing data.
  const phraseMetric = (
    name: string,
    logGroup: logs.ILogGroup,
    pattern: string,
  ) => {
    new logs.MetricFilter(obs, name + "Filter", {
      logGroup,
      filterPattern: logs.FilterPattern.literal(pattern),
      metricNamespace: METRIC_NAMESPACE,
      metricName: name,
      metricValue: "1",
      defaultValue: 0,
    });
    return new cw.Metric({
      namespace: METRIC_NAMESPACE,
      metricName: name,
      statistic: "Sum",
    });
  };
  alarm(
    "ApiUnhealthy",
    targets.metrics.healthyHostCount({ statistic: "Minimum" }),
    1,
    cw.ComparisonOperator.LESS_THAN_THRESHOLD,
  );
  alarm(
    "DatabaseStorage",
    db.metricFreeStorageSpace(),
    4 * 1024 ** 3,
    cw.ComparisonOperator.LESS_THAN_THRESHOLD,
  );
  alarm("DatabaseCpu", db.metricCPUUtilization(), 85);
  // A RunTask that started is not a refresh that happened: RunTask returns once the task is placed and
  // never reads its exit code. The schedule's health is therefore measured on the task's own success
  // line, and the first signal is that line's ABSENCE - the only one that also catches a schedule that
  // was disabled or expired, a role that lost ecs:RunTask, an image that will not start, and a run that
  // died before it logged anything.
  //
  // The window is eighteen 1 h periods. CloudWatch periods sit on the clock, not on the last run, so an
  // alarm of N periods of length P fires between N*P and N*P+P after the last success, depending on
  // where in its period that success fell. Coarse periods waste the margin on alignment: three 6 h ones
  // fire anywhere from 18 h to 24 h - at worst at the very instant a set goes stale at PT24H. With 1 h
  // periods it is 18 h to 19 h, so the owner always has five hours before the screen loses the data.
  // Runs 12 h apart leave at most twelve empty periods between them (eleven, plus one if a run slips by
  // its retry window), so healthy operation never reaches eighteen.
  //
  // A brand-new alarm has no history: the periods before the stack existed are missing, and missing is
  // breaching here, so it goes to ALARM on the deploy that creates it and stays there until the first
  // success line. That is accepted rather than suppressed - any setting that keeps it quiet at bring-up
  // also keeps it quiet when the schedule silently stops, which is the case it exists for. The owner's
  // sheet runs the forecast by hand right after the deploy (step 3-2), which turns it OK within minutes.
  const occurrence = {
    evaluationPeriods: 1,
    treatMissingData: cw.TreatMissingData.NOT_BREACHING,
  };
  // The API service refreshes in place, so its success line is the signal. A missing run or an
  // already-stale response is not allowed to claim LIVE; twelve empty minutes warn on a stopped
  // five-minute loop, while allowing one late provider publication without a false failure page.
  alarm(
    "SeoulLiveRefreshMissing",
    phraseMetric("SeoulLiveCollectOk", apiLogs, '"seoul_live_collect live=true"')
      .with({ period: cdk.Duration.minutes(1) }),
    1,
    cw.ComparisonOperator.LESS_THAN_THRESHOLD,
    { evaluationPeriods: 12 },
  );
  alarm(
    "SeoulLiveRefreshFailed",
    phraseMetric("SeoulLiveCollectFailures", apiLogs, '"seoul_live_collect_failed"')
      .with({ period: cdk.Duration.minutes(1) }),
    1,
    cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
    occurrence,
  );
  alarm(
    "ForecastRefreshMissing",
    phraseMetric(
      "ForecastRefreshOk",
      migrationLogs,
      `"${FORECAST_DONE_PHRASE}" "failed=0"`,
    ).with({ period: cdk.Duration.hours(FORECAST_MISSING_PERIOD_HOURS) }),
    1,
    cw.ComparisonOperator.LESS_THAN_THRESHOLD,
    { evaluationPeriods: FORECAST_MISSING_PERIODS },
  );
  // The named, faster half: a run that happened and refused, or lost a place, in either mode (the two
  // share the phrase). One line is enough, and an empty window is not a failure - hence one period and
  // NOT_BREACHING, unlike the alarm above. A detail refresh that fails reaches the forecast too, within
  // the seven days its snapshot lives, so this is also the detail schedule's only direct signal.
  alarm(
    "DemoRefreshFailed",
    phraseMetric(
      "DemoRefreshFailures",
      migrationLogs,
      `"${DEMO_REFRESH_FAILED_PHRASE}"`,
    ).with({ period: cdk.Duration.minutes(5) }),
    1,
    cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
    occurrence,
  );
  // The outage the success metric cannot see: the provider answered, the run counted the place as
  // refreshed, and nothing was stored. Only for the INT-04 place - another demo place with no forecast
  // is the provider's coverage, not an incident, and alarming on it would ring twice a day forever.
  alarm(
    "ForecastRefreshEmpty",
    phraseMetric(
      "ForecastRefreshEmpty",
      migrationLogs,
      `"${FORECAST_EVIDENCE_TAG} contentId=${INT04_CONTENT_ID} ${FORECAST_EMPTY_TERM}"`,
    ).with({ period: cdk.Duration.minutes(5) }),
    1,
    cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
    occurrence,
  );
  // OpsAlarm.Name. The application has written these lines since BA-072, and its javadoc says "a metric
  // filter matches the quoted phrase" - but no filter existed, so the vocabulary reached no one. Each
  // name gets its own metric and its own alarm: a merged one would say "something happened" about five
  // different incidents with five different responses.
  const pascal = (name: string) =>
    name
      .split("_")
      .map((w) => w[0] + w.slice(1).toLowerCase())
      .join("");
  for (const name of OPS_ALARM_NAMES)
    alarm(
      "OpsAlarm" + pascal(name),
      phraseMetric(
        "OpsAlarm" + pascal(name),
        apiLogs,
        `"ops.alarm name=${name}"`,
      ).with({ period: cdk.Duration.minutes(5) }),
      1,
      cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      occurrence,
    );
  // No AWS::Budgets::Budget: this account is an AWS Organizations member whose SCP explicitly denies
  // budgets:* and ce:GetCostAndUsage (simulate-principal-policy, 2026-09-18). A Budget resource would
  // fail creation and roll the whole stack back. Spend is checked by the owner in the organization's
  // billing view; the operator's cost gate stays an approved estimate, never a live billing reading.
  out(obs, "AlarmTopicArn", topic.topicArn);
  return { foundation, network, data, platform, migration: migrationStack, globalWaf, web, services, obs };
}

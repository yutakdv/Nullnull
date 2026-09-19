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
// CloudFront Function (cloudfront-js-2.0, crypto.createHash sha256 supported) for /api/*. ${Open} and
// ${Verifier} are CloudFormation Fn::Sub variables; the code itself must not contain "${".
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
  config: { account: string; release: Release; webDirectory: string; bootstrapOnly?: boolean },
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
      APP_RELEASE_VERSION: config.release.releaseVersion,
    },
    secrets: {
      SPRING_DATASOURCE_USERNAME: ecs.Secret.fromSecretsManager(appDb, "username"),
      SPRING_DATASOURCE_PASSWORD: ecs.Secret.fromSecretsManager(appDb, "password"),
      NULLNULL_CURSOR_SECRET: ecs.Secret.fromSecretsManager(cursor),
      NULLNULL_DELETION_TOKEN_SECRET: ecs.Secret.fromSecretsManager(deletion),
      KTO_SERVICE_KEY: ecs.Secret.fromSecretsManager(kto),
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
  const spa = new cf.Function(web, "SpaRewrite", {
    functionName: "nullnull-stg-spa-rewrite",
    code: cf.FunctionCode.fromInline(
      "function handler(e){var r=e.request;if(r.uri.indexOf('/api')===0)return {statusCode:404};if(r.uri.indexOf('.')===-1)r.uri='/index.html';return r;}",
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
  new deploy.BucketDeployment(web, "WebRelease", {
    destinationBucket: webBucket,
    sources: [deploy.Source.asset(config.webDirectory)],
    prune: false,
    retainOnDelete: true,
    cacheControl: [deploy.CacheControl.noCache()],
    distribution: dist,
    distributionPaths: ["/*"],
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
      // C3 place reads stay 503 until the operator records staging KTO provenance and flips this file.
      NULLNULL_CATALOG_PUBLIC_ENABLED: String(stagingConfig.catalogPublicEnabled),
      // The submission build runs ITEM optimization (owner decision 2026-09-19, docs/operations/ENVIRONMENT.md).
      // A settled product decision rather than an operator gate, so it is fixed here and not in staging.config.json.
      FEATURE_OPTIMIZATION_ITEM: "true",
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
  const alarm = (
    name: string,
    metric: cw.IMetric,
    threshold: number,
    comparisonOperator = cw.ComparisonOperator
      .GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
  ) => {
    const a = new cw.Alarm(obs, name, {
      metric,
      threshold,
      evaluationPeriods: 2,
      comparisonOperator,
      treatMissingData: cw.TreatMissingData.BREACHING,
    });
    a.addAlarmAction(new actions.SnsAction(topic));
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
  // No AWS::Budgets::Budget: this account is an AWS Organizations member whose SCP explicitly denies
  // budgets:* and ce:GetCostAndUsage (simulate-principal-policy, 2026-09-18). A Budget resource would
  // fail creation and roll the whole stack back. Spend is checked by the owner in the organization's
  // billing view; the operator's cost gate stays an approved estimate, never a live billing reading.
  out(obs, "AlarmTopicArn", topic.topicArn);
  return { foundation, network, data, platform, migration: migrationStack, globalWaf, web, services, obs };
}

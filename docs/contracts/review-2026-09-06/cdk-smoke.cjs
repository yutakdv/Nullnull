// Isolated package compatibility proof, never the application infra:check implementation.
const path = require('node:path');
const { createRequire } = require('node:module');
const deps = createRequire(path.resolve(process.env.NULLNULL_REVIEW_TOOLCHAIN || __dirname, 'package.json'));
const cdk = deps('aws-cdk-lib');
const app = new cdk.App({ analyticsReporting: false });
const stack = new cdk.Stack(app, 'ContractReviewCompatibility', { analyticsReporting: false });
new cdk.aws_s3.Bucket(stack, 'ReviewArtifact', {
  blockPublicAccess: cdk.aws_s3.BlockPublicAccess.BLOCK_ALL,
  encryption: cdk.aws_s3.BucketEncryption.S3_MANAGED,
  enforceSSL: true,
});
app.synth();

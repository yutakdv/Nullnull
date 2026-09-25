#!/usr/bin/env bash

set -Eeuo pipefail
set +x
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

public_url=''
release_ready=false
expect_edge='closed'

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url)
      public_url="${2:-}"
      shift 2
      ;;
    --release-ready)
      release_ready=true
      shift
      ;;
    --expect-edge)
      [[ $# -ge 2 ]] || fail 'expect-edge-must-be-open-or-closed'
      expect_edge="$2"
      shift 2
      ;;
    *)
      fail 'unknown-smoke-argument'
      ;;
  esac
done

[[ "$expect_edge" == 'closed' || "$expect_edge" == 'open' ]] || fail 'expect-edge-must-be-open-or-closed'
require_command curl
require_command node
NULLNULL_READ_ONLY=true assert_operator_contract
[[ "$release_ready" != 'true' ]] || assert_alarm_contacts true

if [[ -z "$public_url" ]]; then
  public_url="$(stack_output NullnullStgWebEdge PublicUrl)"
fi
[[ "$public_url" =~ ^https://[^/]+/?$ ]] || fail 'public-url-must-be-https-origin'
public_url="${public_url%/}"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
chmod 700 "$work_dir"

assert_status() {
  local path="$1" expected="$2" status
  status="$(curl --silent --show-error --output /dev/null \
    --max-time 15 --write-out '%{http_code}' "$public_url$path")"
  [[ "$status" == "$expected" ]] || fail "unexpected-http-status-${path//\//-}"
}

assert_status '/' '200'

# What an anonymous request gets from the API through the edge. Closed (the default, and every fresh release):
# the gate's 503 problem+json. Open (--expect-edge open, the judging period after a --preserve-open-edge deploy,
# A-061/A-069): the API's own health. The same question staging-flows.mjs --expect-edge asks; without it the
# infrastructure lines below could not be produced for a release the judges are using.
edge_answer="$(curl --silent --show-error --output "$work_dir/edge.json" --max-time 15 \
  --write-out '%{http_code} %{content_type}' "$public_url/api/v1/health/live")"
if [[ "$expect_edge" == 'closed' ]]; then
  [[ "$edge_answer" == '503 application/problem+json' ]] || fail 'public-api-edge-not-closed'
else
  [[ "${edge_answer%% *}" == '200' ]] || fail 'public-api-edge-not-open'
  node "$NULLNULL_REPO_ROOT/scripts/aws/validate-health.mjs" "$work_dir/edge.json" live "${edge_answer#* }" \
    >/dev/null 2>&1 || fail 'public-api-edge-not-open'
fi

# The verifier token reaches the API through the same CloudFront -> VPC origin -> ALB path. It is read
# from a 0600 header file, never from argv, and the gate strips it before the origin.
if [[ -n "${NULLNULL_VERIFIER_TOKEN:-}" ]]; then
  ( umask 077 && printf 'x-nullnull-verifier: %s\n' "$NULLNULL_VERIFIER_TOKEN" >"$work_dir/verifier.header" )
  for kind in live ready; do
    body_file="$work_dir/$kind.json"
    metadata="$(curl --silent --show-error --fail --max-time 15 --output "$body_file" \
      -H "@$work_dir/verifier.header" \
      --write-out '%{content_type}' "$public_url/api/v1/health/$kind")" || fail 'health-http-failed'
    node "$NULLNULL_REPO_ROOT/scripts/aws/validate-health.mjs" "$body_file" "$kind" "$metadata" \
      || fail 'health-contract-failed'
  done
  verifier_checked=true
else
  verifier_checked=false
fi

alb_arn="$(stack_output NullnullStgServices InternalAlbArn)"
alb_scheme="$(aws_cli elbv2 describe-load-balancers \
  --load-balancer-arns "$alb_arn" --query 'LoadBalancers[0].Scheme' --output text)"
[[ "$alb_scheme" == 'internal' ]] || fail 'alb-is-not-internal'

bucket="$(stack_output NullnullStgWebEdge WebBucketName)"
public_block="$(aws_cli s3api get-public-access-block \
  --bucket "$bucket" \
  --query 'PublicAccessBlockConfiguration.[BlockPublicAcls,IgnorePublicAcls,BlockPublicPolicy,RestrictPublicBuckets]' \
  --output text)"
[[ "$public_block" == $'True\tTrue\tTrue\tTrue' ]] || fail 's3-public-access-block-incomplete'
bucket_public="$(aws_cli s3api get-bucket-policy-status \
  --bucket "$bucket" --query 'PolicyStatus.IsPublic' --output text)"
[[ "$bucket_public" == 'False' ]] || fail 's3-policy-is-public'

database="$(stack_output NullnullStgData DatabaseIdentifier)"
rds_shape="$(aws_cli rds describe-db-instances \
  --db-instance-identifier "$database" \
  --query 'DBInstances[0].[PubliclyAccessible,MultiAZ,BackupRetentionPeriod,DeletionProtection]' \
  --output text)"
read -r publicly_accessible multi_az backup_days deletion_protection <<<"$rds_shape"
[[ "$publicly_accessible" == 'False' ]] || fail 'rds-is-public'
[[ "$multi_az" == 'True' ]] || fail 'rds-is-not-multi-az'
[[ "$backup_days" =~ ^[0-9]+$ && "$backup_days" -ge 14 ]] || fail 'rds-backup-under-14-days'
[[ "$deletion_protection" == 'True' ]] || fail 'rds-deletion-protection-off'

deploy_role="$(stack_output NullnullStgFoundation DeployRoleName)"
aws_cli iam get-role --role-name "$deploy_role" \
  --query 'Role.AssumeRolePolicyDocument' --output json >"$work_dir/deploy-trust.json"
node "$NULLNULL_REPO_ROOT/scripts/aws/validate-oidc-trust.mjs" "$work_dir/deploy-trust.json" deploy
publish_role="$(stack_output NullnullStgFoundation PublishRoleName)"
aws_cli iam get-role --role-name "$publish_role" \
  --query 'Role.AssumeRolePolicyDocument' --output json >"$work_dir/publish-trust.json"
node "$NULLNULL_REPO_ROOT/scripts/aws/validate-oidc-trust.mjs" "$work_dir/publish-trust.json" publish

printf 'staging_smoke=pass public_https=true public_api_edge=%s verifier_path_checked=%s alb_internal=true s3_private=true rds_private_multi_az=true\n' "$expect_edge" "$verifier_checked"

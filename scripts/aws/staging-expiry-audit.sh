#!/usr/bin/env bash

set -Eeuo pipefail
set +x
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

NULLNULL_READ_ONLY=true assert_operator_contract

expected_stacks=(
  NullnullStgFoundation
  NullnullStgNetwork
  NullnullStgData
  NullnullStgPlatform
  NullnullStgServices
  NullnullStgWebEdge
  NullnullStgObservability
)

missing=0
for stack in "${expected_stacks[@]}"; do
  status="$(aws_cli cloudformation describe-stacks \
    --stack-name "$stack" --query 'Stacks[0].StackStatus' --output text 2>/dev/null || true)"
  if [[ -z "$status" || "$status" == 'None' ]]; then
    printf 'expiry_audit=warning stack=%s reason=missing\n' "$stack"
    missing=1
  else
    printf 'expiry_audit=found stack=%s status=%s\n' "$stack" "$status"
  fi
done

database="$(stack_output NullnullStgData DatabaseIdentifier)"
deletion_protection="$(aws_cli rds describe-db-instances \
  --db-instance-identifier "$database" \
  --query 'DBInstances[0].DeletionProtection' --output text)"
[[ "$deletion_protection" == 'True' ]] || fail 'rds-deletion-protection-off'

printf 'expiry_audit=complete expiry=2026-10-25 missing_stack=%s destructive_action=false\n' "$missing"

aws_cli resourcegroupstaggingapi get-resources \
  --tag-filters Key=Project,Values=Nullnull \
  --query 'ResourceTagMappingList[].ResourceARN' --output json

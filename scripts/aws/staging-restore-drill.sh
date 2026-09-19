#!/usr/bin/env bash

set -Eeuo pipefail
set +x
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

restore_time=''
target_database=''
execute=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --restore-time)
      restore_time="${2:-}"
      shift 2
      ;;
    --target-id)
      target_database="${2:-}"
      shift 2
      ;;
    --execute)
      execute=true
      shift
      ;;
    *)
      fail 'unknown-restore-argument'
      ;;
  esac
done

[[ "$restore_time" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
  || fail 'restore-time-must-be-exact-utc'
if [[ "$execute" == 'true' ]]; then
  assert_operator_contract
else
  NULLNULL_READ_ONLY=true assert_operator_contract
fi

source_database="$(stack_output NullnullStgData DatabaseIdentifier)"
subnet_group="$(stack_output NullnullStgData DatabaseSubnetGroupName)"
database_sg="$(stack_output NullnullStgNetwork RestoreSecurityGroupId)"
[[ "$target_database" =~ ^nullnull-stg-restore-[a-z0-9-]+$ ]] || fail 'explicit-restore-target-required' 

restore_window="$(aws_cli rds describe-db-instances \
  --db-instance-identifier "$source_database" \
  --query 'DBInstances[0].[EarliestRestorableTime,LatestRestorableTime]' \
  --output text)"
read -r earliest latest <<<"$restore_window"
python3 - "$restore_time" "$earliest" "$latest" <<'PYTIME'
import datetime, sys
try:
    requested, first, last = [datetime.datetime.fromisoformat(v.replace('Z', '+00:00')) for v in sys.argv[1:]]
    if not first <= requested <= last: raise ValueError()
except ValueError:
    sys.exit(1)
PYTIME

printf 'restore_drill=plan source=%s target=%s restore_time=%s earliest=%s latest=%s public=false auto_cleanup=false\n' \
  "$source_database" "$target_database" "$restore_time" "$earliest" "$latest"

if [[ "$execute" != 'true' ]]; then
  exit 0
fi

require_nonempty NULLNULL_RESTORE_DRILL_APPROVAL
[[ "$NULLNULL_RESTORE_DRILL_APPROVAL" == 'BA-072-T1' ]] || fail 'invalid-restore-drill-approval'

aws_cli rds restore-db-instance-to-point-in-time \
  --source-db-instance-identifier "$source_database" \
  --target-db-instance-identifier "$target_database" \
  --restore-time "$restore_time" \
  --db-instance-class db.t4g.micro \
  --db-subnet-group-name "$subnet_group" \
  --vpc-security-group-ids "$database_sg" \
  --no-publicly-accessible \
  --no-multi-az \
  --no-deletion-protection \
  --tags \
    Key=Project,Value=Nullnull \
    Key=Environment,Value=staging-restore-drill \
    Key=ManagedBy,Value=operator-script \
    Key=Expiry,Value=2026-10-25 >/dev/null

aws_cli rds wait db-instance-available --db-instance-identifier "$target_database"
is_public="$(aws_cli rds describe-db-instances \
  --db-instance-identifier "$target_database" \
  --query 'DBInstances[0].PubliclyAccessible' --output text)"
[[ "$is_public" == 'False' ]] || fail 'restore-db-became-public'

printf 'restore_drill=awaiting-tombstone-verification target=%s auto_cleanup=false\n' "$target_database"

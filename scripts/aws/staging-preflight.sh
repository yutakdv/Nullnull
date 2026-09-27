#!/usr/bin/env bash

set -Eeuo pipefail
set +x
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

require_secondary=false
config_only=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-ready)
      require_secondary=true
      shift
      ;;
    --config-only)
      config_only=true
      shift
      ;;
    *)
      fail 'unknown-preflight-argument'
      ;;
  esac
done

require_command node
require_command npm
require_command git
assert_operator_contract
assert_alarm_contacts "$require_secondary"

if [[ "$config_only" != 'true' ]]; then
  require_infra_scaffold
  git -C "$NULLNULL_REPO_ROOT" diff --quiet || fail 'dirty-tracked-worktree'
  git -C "$NULLNULL_REPO_ROOT" diff --cached --quiet || fail 'dirty-index'
fi

printf 'staging_preflight=pass region=ap-northeast-2 budget_usd=200 expiry=%s secondary_required=%s\n' \
  "$NULLNULL_EXPIRY_DATE" "$require_secondary"

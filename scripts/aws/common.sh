#!/usr/bin/env bash

# Shared fail-closed helpers for Nullnull staging operations.
# This file is sourced by executable scripts and must not print environment values.

set -Eeuo pipefail
set +x

NULLNULL_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly NULLNULL_REPO_ROOT

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
NULLNULL_STACK_PREFIX="${NULLNULL_STACK_PREFIX:-nullnull-stg}"
NULLNULL_BUDGET_LIMIT_USD="${NULLNULL_BUDGET_LIMIT_USD:-200}"
NULLNULL_EXPIRY_DATE="${NULLNULL_EXPIRY_DATE:-2026-10-31}"
# profile: a named local profile (operator role); ambient: the GitHub OIDC session in the environment.
NULLNULL_AWS_AUTH="${NULLNULL_AWS_AUTH:-profile}"
export AWS_REGION NULLNULL_STACK_PREFIX NULLNULL_BUDGET_LIMIT_USD NULLNULL_EXPIRY_DATE NULLNULL_AWS_AUTH

fail() {
  printf 'staging_ops=failed reason=%s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing-command-$1"
}

require_nonempty() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "missing-$name"
}

is_email() {
  [[ "$1" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]
}

aws_cli() {
  case "$NULLNULL_AWS_AUTH" in
    profile)
      # A selected profile must not silently inherit another principal's ambient credentials.
      env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
        -u AWS_WEB_IDENTITY_TOKEN_FILE -u AWS_ROLE_ARN \
        aws --no-cli-pager --profile "$AWS_PROFILE" --region "$AWS_REGION" "$@"
      ;;
    ambient)
      env -u AWS_PROFILE -u AWS_DEFAULT_PROFILE aws --no-cli-pager --region "$AWS_REGION" "$@"
      ;;
    *)
      fail 'invalid-NULLNULL_AWS_AUTH'
      ;;
  esac
}

expected_role() {
  case "$NULLNULL_AWS_AUTH" in
    profile) printf 'nullnull-stg-operator' ;;
    ambient) printf 'nullnull-stg-github-deploy' ;;
    *) fail 'invalid-NULLNULL_AWS_AUTH' ;;
  esac
}

# Account, region, principal and expiry. Contacts are a separate contract (assert_alarm_contacts):
# a CI smoke has no business holding an operator's e-mail address.
assert_operator_contract() {
  require_command aws
  require_nonempty NULLNULL_AWS_ACCOUNT_ID
  if [[ "$NULLNULL_AWS_AUTH" == 'profile' ]]; then
    require_nonempty AWS_PROFILE
  else
    [[ "${GITHUB_ACTIONS:-}" == 'true' ]] || fail 'ambient-auth-outside-github-actions'
    require_nonempty AWS_SESSION_TOKEN
  fi

  [[ "$AWS_REGION" == 'ap-northeast-2' ]] || fail 'unexpected-region'
  [[ "$NULLNULL_STACK_PREFIX" == 'nullnull-stg' ]] || fail 'unexpected-stack-prefix'
  [[ "$NULLNULL_BUDGET_LIMIT_USD" == '200' ]] || fail 'unexpected-budget-limit'
  [[ "$NULLNULL_EXPIRY_DATE" == '2026-10-31' ]] || fail 'unexpected-expiry-date'
  [[ "$NULLNULL_AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || fail 'invalid-account-id'

  local today identity_arn
  today="$(date -u +%F)"
  [[ "${NULLNULL_READ_ONLY:-false}" == true || "$today" < "$NULLNULL_EXPIRY_DATE" || "$today" == "$NULLNULL_EXPIRY_DATE" ]] \
    || fail 'staging-expired'

  identity_arn="$(aws_cli sts get-caller-identity --query Arn --output text)"
  [[ "$identity_arn" == "arn:aws:sts::${NULLNULL_AWS_ACCOUNT_ID}:assumed-role/$(expected_role)/"* ]] \
    || fail 'unexpected-aws-account-or-principal'
}

assert_alarm_contacts() {
  local require_secondary="${1:-false}"
  require_nonempty NULLNULL_ALARM_PRIMARY_EMAIL
  is_email "$NULLNULL_ALARM_PRIMARY_EMAIL" || fail 'invalid-primary-email'
  if [[ "$require_secondary" == 'true' ]]; then
    require_nonempty NULLNULL_ALARM_SECONDARY_EMAIL
    is_email "$NULLNULL_ALARM_SECONDARY_EMAIL" || fail 'invalid-secondary-email'
    [[ "$NULLNULL_ALARM_PRIMARY_EMAIL" != "$NULLNULL_ALARM_SECONDARY_EMAIL" ]] \
      || fail 'primary-and-secondary-must-differ'
  elif [[ -n "${NULLNULL_ALARM_SECONDARY_EMAIL:-}" ]]; then
    is_email "$NULLNULL_ALARM_SECONDARY_EMAIL" || fail 'invalid-secondary-email'
  fi
}

stack_output() {
  local stack="$1" key="$2" region="${3:-$AWS_REGION}" value
  value="$(AWS_REGION="$region" aws_cli cloudformation describe-stacks \
    --stack-name "$stack" \
    --query "Stacks[0].Outputs[?OutputKey=='${key}'].OutputValue | [0]" \
    --output text)"
  [[ -n "$value" && "$value" != 'None' ]] || fail "missing-output-${stack}-${key}"
  printf '%s' "$value"
}

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$path" | awk '{print $1}'
  else
    fail 'missing-sha256-tool'
  fi
}

require_infra_scaffold() {
  [[ -f "$NULLNULL_REPO_ROOT/infra/package.json" ]] || fail 'infra-not-scaffolded'
  [[ -f "$NULLNULL_REPO_ROOT/infra/package-lock.json" ]] || fail 'infra-lockfile-missing'
}

#!/usr/bin/env bash
# BA-006-T3: a GitHub OIDC token from the wrong environment is refused by the other environment's role.
#
# Run inside a GitHub Actions job that has `id-token: write` and a deployment environment. The job's own role
# must accept the token - that control is what makes the refusal mean "wrong subject" and not
# "the token or the call was broken" - and the other environment's role (OTHER_ROLE) must answer AccessDenied.
# Any other outcome is no verdict and fails. No credential is printed: only the call's exit and error code.
set -euo pipefail

: "${ACTIONS_ID_TOKEN_REQUEST_URL:?the job needs permissions: id-token: write}"
: "${ACTIONS_ID_TOKEN_REQUEST_TOKEN:?the job needs permissions: id-token: write}"
: "${ACCOUNT:?}" "${ENVIRONMENT:?}"
# The roles come from the environment, not from inputs: a mistyped or stale role name answers AccessDenied too,
# and would read as a refusal. Each environment's own role and the other one are the two real GitHub roles.
case "$ENVIRONMENT" in
  staging-build) OWN_ROLE=nullnull-stg-github-publish; OTHER_ROLE=nullnull-stg-github-deploy ;;
  staging) OWN_ROLE=nullnull-stg-github-deploy; OTHER_ROLE=nullnull-stg-github-publish ;;
  *) echo "oidc_probe=no-verdict reason=unknown-environment-${ENVIRONMENT}"; exit 1 ;;
esac

token="$(curl --fail --silent --show-error \
  -H "Authorization: bearer ${ACTIONS_ID_TOKEN_REQUEST_TOKEN}" \
  "${ACTIONS_ID_TOKEN_REQUEST_URL}&audience=sts.amazonaws.com" |
  python3 -c 'import json,sys; print(json.load(sys.stdin).get("value") or "")')"
[[ -n "$token" ]] || { echo "oidc_probe=no-verdict reason=no-token"; exit 1; }
echo "::add-mask::${token}"

# The subject this token carries: its environment must be the job's, or the probe is not testing what it names.
subject="$(python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; print(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))).get("sub",""))' "$token")"
[[ "$subject" == *":environment:${ENVIRONMENT}" ]] ||
  { echo "oidc_probe=no-verdict reason=token-not-from-environment-${ENVIRONMENT}"; exit 1; }
echo "oidc_token_subject_environment=${ENVIRONMENT}"

assume() {
  local role="$1" err
  err="$(mktemp)"
  if aws sts assume-role-with-web-identity --role-arn "arn:aws:iam::${ACCOUNT}:role/${role}" \
      --role-session-name "oidc-negative-${GITHUB_RUN_ID:-local}" --web-identity-token "$token" \
      --duration-seconds 900 --query 'AssumedRoleUser.AssumedRoleId' --output text >/dev/null 2>"$err"; then
    echo accepted
  elif grep -q '(AccessDenied) when calling the AssumeRoleWithWebIdentity operation' "$err"; then
    echo denied
  else
    echo "other:$(grep -oE '\(([A-Za-z]+)\)' "$err" | head -1 | tr -d '()')"
  fi
  rm -f "$err"
}

own="$(assume "$OWN_ROLE")"
[[ "$own" == accepted ]] ||
  { echo "oidc_probe=no-verdict reason=control-role-${OWN_ROLE}-${own}"; exit 1; }
echo "oidc_control=accepted role=${OWN_ROLE}"

other="$(assume "$OTHER_ROLE")"
case "$other" in
  denied) echo "oidc_negative=rejected role=${OTHER_ROLE} subject_environment=${ENVIRONMENT}" ;;
  accepted) echo "oidc_negative=ACCEPTED role=${OTHER_ROLE} subject_environment=${ENVIRONMENT}"; exit 1 ;;
  *) echo "oidc_probe=no-verdict reason=${OTHER_ROLE}-${other}"; exit 1 ;;
esac

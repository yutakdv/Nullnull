#!/usr/bin/env bash

set -Eeuo pipefail
set +x
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

execute=false
release_ready=false
send_test=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --execute)
      execute=true
      shift
      ;;
    --release-ready)
      release_ready=true
      shift
      ;;
    --send-test)
      send_test=true
      shift
      ;;
    *)
      fail 'unknown-alarm-argument'
      ;;
  esac
done

assert_operator_contract
assert_alarm_contacts "$release_ready"
if [[ "$send_test" == 'true' ]]; then
  [[ "$release_ready" == 'true' ]] || fail 'test-alarm-requires-release-ready-mode'
  require_nonempty NULLNULL_ALARM_TEST_APPROVAL
  [[ "$NULLNULL_ALARM_TEST_APPROVAL" == 'BA-072-T3' ]] || fail 'invalid-alarm-test-approval'
fi
topic_arn="$(stack_output NullnullStgObservability AlarmTopicArn)"

if [[ "$execute" != 'true' ]]; then
  printf 'alarm_action=plan primary_configured=true secondary_required=%s send_test=%s\n' \
    "$release_ready" "$send_test"
  exit 0
fi

subscribe_if_missing() {
  local email="$1" existing
  existing="$(aws_cli sns list-subscriptions-by-topic \
    --topic-arn "$topic_arn" \
    --query "Subscriptions[?Protocol=='email' && Endpoint=='${email}'].SubscriptionArn | [0]" \
    --output text)"
  if [[ -z "$existing" || "$existing" == 'None' ]]; then
    aws_cli sns subscribe --topic-arn "$topic_arn" --protocol email \
      --notification-endpoint "$email" >/dev/null
  fi
}

subscribe_if_missing "$NULLNULL_ALARM_PRIMARY_EMAIL"
if [[ -n "${NULLNULL_ALARM_SECONDARY_EMAIL:-}" ]]; then
  subscribe_if_missing "$NULLNULL_ALARM_SECONDARY_EMAIL"
fi

if [[ "$send_test" == 'true' ]]; then
  [[ "$release_ready" == 'true' ]] || fail 'test-alarm-requires-release-ready-mode'
  require_nonempty NULLNULL_ALARM_TEST_APPROVAL
  [[ "$NULLNULL_ALARM_TEST_APPROVAL" == 'BA-072-T3' ]] || fail 'invalid-alarm-test-approval'

  for email_var in NULLNULL_ALARM_PRIMARY_EMAIL NULLNULL_ALARM_SECONDARY_EMAIL; do
    email="${!email_var}"
    subscription="$(aws_cli sns list-subscriptions-by-topic \
      --topic-arn "$topic_arn" \
      --query "Subscriptions[?Protocol=='email' && Endpoint=='${email}'].SubscriptionArn | [0]" \
      --output text)"
    [[ -n "$subscription" && "$subscription" != 'None' && "$subscription" != 'PendingConfirmation' ]] \
      || fail 'alarm-subscription-not-confirmed'
  done

  aws_cli sns publish --topic-arn "$topic_arn" \
    --subject '[TEST] Nullnull staging alarm' \
    --message 'Synthetic BA-072-T3 delivery test. No incident and no user data.' >/dev/null
fi

printf 'alarm_action=executed primary_subscription_requested=true secondary_required=%s test_sent=%s\n' \
  "$release_ready" "$send_test"

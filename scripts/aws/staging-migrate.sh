#!/usr/bin/env bash
set -Eeuo pipefail
set +x
printf '%s\n' 'staging_ops=failed reason=migration-requires-locked-deploy-plan' >&2
exit 1

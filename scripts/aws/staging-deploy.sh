#!/usr/bin/env bash
set -Eeuo pipefail
set +x
# --execute requires --plan and --approved-plan-sha256 (legacy --approved-diff-sha256 alias).
exec python3 "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/staging_operator.py" deploy "$@"

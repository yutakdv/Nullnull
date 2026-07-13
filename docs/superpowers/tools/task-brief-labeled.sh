#!/usr/bin/env bash
# Extract one task's full text (by label like B1/F1/A3/E2) from the plan into a file.
# The stock superpowers `task-brief` script only matches numeric "Task N" headings;
# this plan uses "Task B1", "Task F1" etc., so use this instead.
#
# Usage: task-brief-labeled.sh PLAN_FILE TASK_ID OUTFILE
#   e.g. task-brief-labeled.sh docs/superpowers/plans/2026-07-13-congestion-dispersion-api-enhancement.md B1 .superpowers/sdd/task-B1-brief.md
set -euo pipefail
plan=$1; id=$2; out=$3
awk -v id="$id" '
  /^```/ { infence = !infence; if (intask) print; next }
  !infence && $0 ~ ("^#+[ \t]+Task[ \t]+" id "[^0-9A-Za-z]") { intask=1 }
  intask && !infence && /^---[ \t]*$/ { exit }
  intask { print }
' "$plan" > "$out"
[ -s "$out" ] || { echo "task $id not found in $plan" >&2; exit 3; }
echo "wrote $out: $(wc -l < "$out" | tr -d ' ') lines"

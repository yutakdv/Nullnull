#!/usr/bin/env bash

set -Eeuo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly marker_path="${project_root}/.nullnull-target-stack"
readonly artifact_dir="${project_root}/.artifacts/integration"
readonly compose_file="${project_root}/compose.integration.yml"
readonly target_stack_verifier="${project_root}/scripts/verify_target_stack.py"
readonly evaluation_report_checker="${project_root}/scripts/check_evaluation_report.py"
readonly test_report_checker="${project_root}/scripts/check_test_reports.py"
readonly e2e_flaky_checker="${project_root}/scripts/check_e2e_flaky.py"
readonly script_tests_runner="${project_root}/scripts/run_script_tests.py"
readonly gate_evidence_recorder="${project_root}/scripts/record_gate_evidence.py"
readonly egress_report_checker="${project_root}/scripts/check_egress_report.py"
readonly npm_audit_report_checker="${project_root}/scripts/check_npm_audit_report.py"
readonly infra_report_checker="${project_root}/scripts/check_infra_report.py"

compose_available=false
compose=()

mkdir -p "${artifact_dir}"
echo "not-completed" >"${artifact_dir}/mode.txt"
echo "running" >"${artifact_dir}/status.txt"
cd "${project_root}"

collect_status_and_teardown() {
  local exit_code=$?
  trap - EXIT
  if [[ "${exit_code}" -eq 0 ]]; then
    echo "success" >"${artifact_dir}/status.txt"
  else
    echo "failed" >"${artifact_dir}/status.txt"
  fi

  if [[ "${compose_available}" == true ]]; then
    set +e
    "${compose[@]}" ps --all >"${artifact_dir}/compose-ps.txt" 2>&1
    "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1
    "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1
    set -e
  fi

  exit "${exit_code}"
}
trap collect_status_and_teardown EXIT

if [[ ! -f "${marker_path}" ]]; then
  if [[ -d apps/web || -d apps/api ]]; then
    echo "Target app directory exists but .nullnull-target-stack is missing." >&2
    echo "The M0 scaffold must add the marker and complete Docker integration in the same PR." >&2
    exit 1
  fi

  python3 scripts/validate_docs.py
  echo "baseline-only" >"${artifact_dir}/mode.txt"
  echo "integration_mode=baseline-only"
  echo "Target apps are not scaffolded; repository-specific document and contract traceability passed."
  exit 0
fi

required_paths=(
  "apps/api/Dockerfile"
  "apps/api/gradlew"
  "apps/api/gradle/wrapper/gradle-wrapper.jar"
  "apps/api/gradle/wrapper/gradle-wrapper.properties"
  "apps/web/Dockerfile"
  "apps/web/package.json"
  # apps/web is an npm workspace member, and npm keeps exactly one lockfile at the root.
  # A per-app lockfile cannot resolve the workspace sibling @nullnull/api-client, so requiring
  # one here would be unsatisfiable. The root lockfile already pins apps/web's dependencies.
  "package.json"
  "package-lock.json"
  "compose.integration.yml"
  "docs/api/openapi.yaml"
  "scripts/verify_target_stack.py"
)

for required_path in "${required_paths[@]}"; do
  if [[ ! -f "${required_path}" ]]; then
    echo "Target-stack marker exists but required integration artifact is missing: ${required_path}" >&2
    exit 1
  fi
done

python3 "${target_stack_verifier}"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required after the target-stack marker is committed." >&2
  exit 1
fi

docker compose version

# REC-CI-6: evaluation.json records the commit it describes; compose passes this to ai-quality.
APP_GIT_SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
export APP_GIT_SHA

# --profile quality makes the quality-only services visible to `config`, so the compose contract check sees them.
compose=(docker compose --project-name nullnull-pr --profile quality --file "${compose_file}")
compose_available=true

"${compose[@]}" config --format json >"${artifact_dir}/compose-config.json"
python3 "${target_stack_verifier}" \
  --compose-config "${artifact_dir}/compose-config.json"
"${compose[@]}" build --pull \
  api-quality \
  ai-quality \
  web-quality \
  api-client-diff \
  security-scan \
  infra-plan \
  ai \
  api \
  web \
  e2e
"${compose[@]}" up --detach postgres
touch "${artifact_dir}/quality-run-start"
"${compose[@]}" run --rm api-quality
"${compose[@]}" run --rm ai-quality

# REC-CI-6: the recommendation evaluation report is merge evidence, so a missing artifact fails here
# even when the suite itself was green. A report that records a partial corpus or a non-empty
# safety.failures is not evidence either, so its content is re-checked outside the container.
readonly recommendation_report="${artifact_dir}/recommendation-ai/evaluation.json"
if [[ ! -f "${recommendation_report}" ]]; then
  echo "Recommendation evaluation report is missing: ${recommendation_report}" >&2
  exit 1
fi
python3 "${evaluation_report_checker}" "${recommendation_report}"
# Evidence is produced before it is aggregated. This probe moved above the report checker
# when its verdict became collectable: run afterwards, it wrote gateChecks into a directory
# the checker had already read, so BA-004-T3 would have been missing on every run while the
# file sat there looking like evidence.
# BA-004-T3: the probe's exit code is only evidence while the probe is intact. It prints a verdict
# token for exactly that reason, and nothing was reading it - so a command changed to something
# that does not probe would exit 0 and pass. Capture the output and judge the token, the same way
# infra-plan and the npm audit report are judged.
readonly egress_report="${artifact_dir}/egress-denied.txt"
rm -f "${egress_report}"
"${compose[@]}" run --rm egress-denied >"${egress_report}" 2>&1 || {
  cat "${egress_report}" >&2
  echo "egress-denied exited non-zero" >&2
  exit 1
}
cat "${egress_report}"
python3 "${egress_report_checker}" "${egress_report}"
# BA-004-T3 asks for reproduction in the real Compose run, which no testcase can assert.
# BA-006-T1 rides the same verdict rather than a second one: its two clauses are the
# internal network and the outbound-deny probe, and the probe IS the network's proof -
# it runs attached to integration-internal and curl has to fail. Were that network not
# internal the curl would succeed and the probe would exit 1, so one token answers both.
# The line above already refused a probe that stated nothing; this turns the verdict it
# accepted into evidence the aggregator can read, and refuses if the token is absent.
python3 "${gate_evidence_recorder}" \
  --out "${artifact_dir}/gate-evidence" \
  --report "${egress_report}" \
  --require "outbound_network=denied" \
  --name "BA-004-T3 BA-006-T1 egress denial reproduced on the internal Compose network"
# The Python-proven acceptance IDs. Evidence for them is produced in two places and, until this
# ran here, read in only one: api-quality wrote scriptTests and fed it to the same checker, while
# this - the required gate - passed only --junit-dir. BA-001-T1/T3 are provable in Python alone, so
# api-quality went green and docker-integration failed on the same commit. A non-required workflow
# agreeing is not this gate's answer.
python3 "${script_tests_runner}" --out "${artifact_dir}/script-test-results"

"${compose[@]}" run --rm web-quality
"${compose[@]}" run --rm api-client-diff
# A report left behind by an earlier run would satisfy the presence check below even if this run
# never produced one, so the previous artifact is dropped before the exporter runs.
readonly npm_audit_report="${artifact_dir}/audit/npm-audit.json"
rm -f "${npm_audit_report}"
"${compose[@]}" run --rm security-scan

# DX-003: the audit itself runs at image build time, where the registry is still reachable, and
# security-scan only exports the report onto the artifact volume. The gate is judged here instead,
# the same way the recommendation evaluation report is: a report that is absent, unreadable or
# malformed fails, so a scan that never really ran cannot be mistaken for a clean one.
if [[ ! -f "${npm_audit_report}" ]]; then
  echo "npm audit report is missing: ${npm_audit_report}" >&2
  exit 1
fi
python3 "${npm_audit_report_checker}" "${npm_audit_report}"

# infra:check runs the offline CDK synth and assertions in infra/ (BA-006) and exits non-zero when
# they fail, which stops the run here. A zero exit is still not taken as the answer: capture what
# it stated and let the checker decide, so a run that states no outcome at all fails, and blocked
# (infra/ absent) is recorded as blocked and never counted as a pass.
readonly infra_report="${artifact_dir}/infra-check.txt"
rm -f "${infra_report}"
"${compose[@]}" run --rm infra-plan >"${infra_report}" 2>&1 || {
  cat "${infra_report}" >&2
  echo "infra-plan exited non-zero" >&2
  exit 1
}
cat "${infra_report}"
python3 "${infra_report_checker}" "${infra_report}"
"${compose[@]}" up --detach ai api web

# integration-internal is internal: true, so a published port never reaches the host. Both
# readiness probes therefore run inside the network from the ai container, whose runtime image
# ships the Python standard library (no curl, no jq).
readonly readiness_body="${artifact_dir}/api-readiness.json"
readonly api_readiness_url="http://api:8080/api/v1/health/ready"
readonly web_root_url="http://web:4173/"

# Prints the response body when the URL answers HTTP 200; any other status, HTTP error,
# connection failure or timeout exits non-zero without aborting the caller.
fetch_http_ok() {
  "${compose[@]}" exec -T ai python3 -c '
import sys
import urllib.request

try:
    with urllib.request.urlopen(sys.argv[1], timeout=2) as response:
        if response.status != 200:
            sys.exit(1)
        sys.stdout.write(response.read().decode("utf-8", "replace"))
except (OSError, ValueError):
    sys.exit(1)
' "$1"
}

# BA-003: /health/ready answers 200 while an optional probe is DEGRADED, so the HTTP status
# alone would hide an unreachable recommendation service. ReadinessQuery reports READY only
# when every probe, including the optional "recommendation" one, is READY.
api_readiness_is_ready() {
  python3 -c '
import json
import sys

try:
    document = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
sys.exit(0 if isinstance(document, dict) and document.get("status") == "READY" else 1)
' <"${readiness_body}"
}

print_api_readiness_checks() {
  python3 -c '
import json
import sys

try:
    document = json.load(sys.stdin)
except ValueError:
    print("readiness body is not JSON")
    raise SystemExit(0)
if not isinstance(document, dict):
    print("readiness body is not an object")
    raise SystemExit(0)
print("status={}".format(document.get("status")))
checks = document.get("checks")
for check in checks if isinstance(checks, list) else []:
    if isinstance(check, dict):
        print("{}={}".format(check.get("name"), check.get("status")))
' <"${readiness_body}"
}

api_ready=false
web_ready=false
for _ in $(seq 1 60); do
  if fetch_http_ok "${api_readiness_url}" >"${readiness_body}"; then
    if api_readiness_is_ready; then
      api_ready=true
    fi
  fi
  if fetch_http_ok "${web_root_url}" >/dev/null; then
    web_ready=true
  fi
  if [[ "${api_ready}" == true && "${web_ready}" == true ]]; then
    break
  fi
  sleep 2
done

if [[ "${api_ready}" != true || "${web_ready}" != true ]]; then
  echo "Integrated web/API readiness did not complete within 60 attempts." >&2
  if [[ -s "${readiness_body}" ]]; then
    echo "Last API readiness report:" >&2
    print_api_readiness_checks >&2
  fi
  exit 1
fi

# #253: the E2E reads catalog rows that no production path creates here (egress is denied, so the
# KTO ingest cannot run). Seeded only now: api has migrated the schema, and api-quality - which
# shares this database - has finished. The read-back line is the verdict; psql exiting 0 is not.
readonly e2e_seed_report="${artifact_dir}/e2e-catalog-seed.txt"
"${compose[@]}" exec -T postgres psql --no-psqlrc --quiet --tuples-only --no-align \
  -v ON_ERROR_STOP=1 -U nullnull -d nullnull_integration \
  <"${project_root}/scripts/e2e/catalog-seed.sql" >"${e2e_seed_report}" 2>&1 || {
  cat "${e2e_seed_report}" >&2
  exit 1
}
if ! grep -qx 'e2e_catalog_seed=places:3,published_posts:1' "${e2e_seed_report}"; then
  echo "E2E catalog seed did not read back as expected:" >&2
  cat "${e2e_seed_report}" >&2
  exit 1
fi
grep -x 'e2e_catalog_seed=.*' "${e2e_seed_report}"

"${compose[@]}" run --rm e2e
# A retry hides a first-attempt failure from every check that follows: Playwright writes the
# retried test as a plain <testcase> with no <failure> and exits 0, so check_test_reports.py's
# failures=0 requirement is satisfied by a run that was red once. This records the count from the
# json reporter. It does NOT fail the gate - the number is unknown until a full run reports one,
# and a required check with an unknown pass rate is how a gate gets disabled. `none` rather than
# `0` when the report cannot answer.
python3 "${e2e_flaky_checker}" "${artifact_dir}/playwright/e2e/results.json"
# #233: aggregated only now, after the browser suite has written its JUnit, so an acceptance ID a
# Playwright title carries is counted like any other. Earlier it ran above web-quality and could
# only have read a report this run had not produced yet - --run-start rejects anything older than
# quality-run-start, so a report left by an earlier run fails rather than standing in for this one.
python3 "${test_report_checker}" \
  --junit-dir "${artifact_dir}/api-test-results" \
  --script-junit-dir "${artifact_dir}/script-test-results" \
  --gate-junit-dir "${artifact_dir}/gate-evidence" \
  --e2e-junit-dir "${artifact_dir}/playwright" \
  --backend-plan "${project_root}/docs/engineering/backend-plan.json" \
  --manifest "${project_root}/apps/ai/tests/recommendation/manifest.json" \
  --evaluation "${recommendation_report}" \
  --run-start "${artifact_dir}/quality-run-start"
echo "full-docker" >"${artifact_dir}/mode.txt"
echo "integration_mode=full-docker"

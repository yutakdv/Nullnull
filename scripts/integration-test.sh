#!/usr/bin/env bash

set -Eeuo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly marker_path="${project_root}/.nullnull-target-stack"
readonly artifact_dir="${project_root}/.artifacts/integration"
readonly compose_file="${project_root}/compose.integration.yml"
readonly target_stack_verifier="${project_root}/scripts/verify_target_stack.py"

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
  "apps/web/package-lock.json"
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
"${compose[@]}" run --rm api-quality
"${compose[@]}" run --rm ai-quality

# REC-CI-6: the recommendation evaluation report is merge evidence, so a missing artifact fails here
# even when the suite itself was green.
readonly recommendation_report="${artifact_dir}/recommendation-ai/evaluation.json"
if [[ ! -f "${recommendation_report}" ]]; then
  echo "Recommendation evaluation report is missing: ${recommendation_report}" >&2
  exit 1
fi

"${compose[@]}" run --rm web-quality
"${compose[@]}" run --rm api-client-diff
"${compose[@]}" run --rm security-scan
"${compose[@]}" run --rm infra-plan
"${compose[@]}" run --rm egress-denied
"${compose[@]}" up --detach ai api web

# BA-003: /health/ready answers 200 while an optional probe is DEGRADED, so the HTTP status
# alone would hide an unreachable recommendation service. ReadinessQuery reports READY only
# when every probe, including the optional "recommendation" one, is READY.
readonly readiness_body="${artifact_dir}/api-readiness.json"

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
  if curl --fail --silent --show-error \
    http://127.0.0.1:18080/api/v1/health/ready >"${readiness_body}"; then
    if api_readiness_is_ready; then
      api_ready=true
    fi
  fi
  if curl --fail --silent --show-error http://127.0.0.1:14173/ >/dev/null; then
    web_ready=true
  fi
  if [[ "${api_ready}" == true && "${web_ready}" == true ]]; then
    break
  fi
  sleep 2
done

if [[ "${api_ready}" != true || "${web_ready}" != true ]]; then
  echo "Integrated web/API readiness did not complete within 120 seconds." >&2
  if [[ -s "${readiness_body}" ]]; then
    echo "Last API readiness report:" >&2
    print_api_readiness_checks >&2
  fi
  exit 1
fi

"${compose[@]}" run --rm e2e
echo "full-docker" >"${artifact_dir}/mode.txt"
echo "integration_mode=full-docker"

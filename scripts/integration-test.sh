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

compose=(docker compose --project-name nullnull-pr --file "${compose_file}")
compose_available=true

"${compose[@]}" config --format json >"${artifact_dir}/compose-config.json"
python3 "${target_stack_verifier}" \
  --compose-config "${artifact_dir}/compose-config.json"
"${compose[@]}" build --pull \
  api-quality \
  web-quality \
  api-client-diff \
  security-scan \
  infra-plan \
  api \
  web \
  e2e
"${compose[@]}" up --detach postgres
"${compose[@]}" run --rm api-quality
"${compose[@]}" run --rm web-quality
"${compose[@]}" run --rm api-client-diff
"${compose[@]}" run --rm security-scan
"${compose[@]}" run --rm infra-plan
"${compose[@]}" run --rm egress-denied
"${compose[@]}" up --detach api web

api_ready=false
web_ready=false
for _ in $(seq 1 60); do
  if curl --fail --silent --show-error http://127.0.0.1:18080/api/v1/health/ready >/dev/null; then
    api_ready=true
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
  exit 1
fi

"${compose[@]}" run --rm e2e
echo "full-docker" >"${artifact_dir}/mode.txt"
echo "integration_mode=full-docker"

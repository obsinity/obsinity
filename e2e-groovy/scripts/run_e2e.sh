#!/bin/bash
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel)"
E2E_DIR="${ROOT_DIR}/e2e-groovy"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_cmd docker
require_cmd java
require_cmd mvn
require_cmd curl

docker compose version >/dev/null 2>&1 || {
  echo "docker compose is required" >&2
  exit 1
}

RUN_ID="${OBSINITY_RUN_ID:-$(cat /proc/sys/kernel/random/uuid)}"
SHORT_RUN_ID="${RUN_ID:0:8}"
COMPOSE_PROJECT_NAME="obsinity-e2e"
if [[ "${OBSINITY_E2E_PARALLEL:-0}" == "1" ]]; then
  COMPOSE_PROJECT_NAME="obsinity-e2e-${SHORT_RUN_ID}"
fi
export COMPOSE_PROJECT_NAME

ARTIFACTS_DIR="${E2E_DIR}/artifacts/${RUN_ID}"
FAILURES_DIR="${ARTIFACTS_DIR}/failures"
mkdir -p "${FAILURES_DIR}"

BASE_PORT="${OBSINITY_E2E_PORT:-18086}"
export OBSINITY_E2E_PORT="${BASE_PORT}"
BASE_URL="${OBSINITY_BASE_URL:-http://localhost:${BASE_PORT}}"
HEALTH_URL="${OBSINITY_HEALTH_URL:-${BASE_URL}/api/admin/config/ready}"
HEALTH_TIMEOUT="${OBSINITY_HEALTH_TIMEOUT_SECONDS:-120}"
HEALTH_INTERVAL="${OBSINITY_HEALTH_INTERVAL_SECONDS:-2}"

write_summary() {
  local exit_code="$1"
  cat > "${ARTIFACTS_DIR}/summary.json" <<EOF
{
  "runId": "${RUN_ID}",
  "baseUrl": "${BASE_URL}",
  "port": ${BASE_PORT},
  "composeProject": "${COMPOSE_PROJECT_NAME}",
  "exitCode": ${exit_code}
}
EOF
}

collect_artifacts() {
  mkdir -p "${ARTIFACTS_DIR}"
  (cd "${E2E_DIR}" && docker compose -p "${COMPOSE_PROJECT_NAME}" logs --no-color > "${ARTIFACTS_DIR}/docker.log" || true)
  (cd "${E2E_DIR}" && docker compose -p "${COMPOSE_PROJECT_NAME}" ps > "${ARTIFACTS_DIR}/docker.ps.txt" || true)

  local app_container
  app_container="$(docker ps -a --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}" \
    --filter "label=com.docker.compose.service=app" -q | head -n 1)"
  if [[ -n "${app_container}" ]]; then
    docker inspect "${app_container}" > "${ARTIFACTS_DIR}/docker.inspect.json" || true
  fi

  local junit_source
  junit_source="$(ls "${E2E_DIR}/target/surefire-reports"/TEST-*.xml 2>/dev/null | head -n 1 || true)"
  if [[ -n "${junit_source}" ]]; then
    cp "${junit_source}" "${ARTIFACTS_DIR}/junit.xml"
  fi

  if [[ -d "${E2E_DIR}/reference-config" ]]; then
    rm -rf "${ARTIFACTS_DIR}/reference-config-snapshot"
    cp -R "${E2E_DIR}/reference-config" "${ARTIFACTS_DIR}/reference-config-snapshot"
  fi
}

cleanup_containers() {
  (cd "${E2E_DIR}" && docker compose -p "${COMPOSE_PROJECT_NAME}" down -v --remove-orphans || true)
  (cd "${E2E_DIR}" && docker compose -p "${COMPOSE_PROJECT_NAME}" rm -f -s -v || true)

  docker ps -a --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}" -q | xargs -r docker rm -f || true
  docker network ls --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}" -q | xargs -r docker network rm || true
  docker image rm -f obsinity-e2e-app || true
}

trap 'exit_code=$?; collect_artifacts; write_summary "$exit_code"; if [[ "${OBSINITY_KEEP_CONTAINERS:-0}" != "1" ]]; then cleanup_containers; fi; exit $exit_code' EXIT

echo "🧹 Cleaning up existing compose project ${COMPOSE_PROJECT_NAME}..."
cleanup_containers

echo "🔄 Building obsinity-reference-service jar..."
(cd "${ROOT_DIR}" && mvn -U -P local -DskipTests clean package -pl obsinity-reference-service -am)

JAR_PATH="$(ls "${ROOT_DIR}/obsinity-reference-service/target/obsinity-reference-service-"*.jar \
  | grep -v ".original" | head -n 1)"
if [[ -z "${JAR_PATH}" ]]; then
  echo "Could not find built reference-service jar" >&2
  exit 1
fi

mkdir -p "${E2E_DIR}/build-context"
cp "${JAR_PATH}" "${E2E_DIR}/build-context/app.jar"

echo "🐳 Building docker images..."
DOCKER_NOCACHE="${OBSINITY_DOCKER_NOCACHE:-1}"
if [[ "${DOCKER_NOCACHE}" == "1" ]]; then
  (cd "${E2E_DIR}" && docker compose -p "${COMPOSE_PROJECT_NAME}" build --no-cache)
else
  (cd "${E2E_DIR}" && docker compose -p "${COMPOSE_PROJECT_NAME}" build)
fi

echo "🚀 Starting stack..."
(cd "${E2E_DIR}" && docker compose -p "${COMPOSE_PROJECT_NAME}" up -d --force-recreate --renew-anon-volumes)

echo "⏳ Waiting for health endpoint: ${HEALTH_URL}"
start_time="$(date +%s)"
while true; do
  if curl -sf "${HEALTH_URL}" >/dev/null 2>&1; then
    break
  fi
  now="$(date +%s)"
  if (( now - start_time > HEALTH_TIMEOUT )); then
    echo "Health check timed out after ${HEALTH_TIMEOUT}s" >&2
    exit 1
  fi
  sleep "${HEALTH_INTERVAL}"
done

echo "🧪 Running tests..."
export OBSINITY_BASE_URL="${BASE_URL}"
export OBSINITY_RUN_ID="${RUN_ID}"
MAVEN_QUIET_FLAG=""
if [[ "${OBSINITY_TEST_QUIET:-0}" == "1" ]]; then
  MAVEN_QUIET_FLAG="-q"
fi
SUREFIRE_ARGS=(
  "-Dsurefire.useFile=false"
  "-Dsurefire.reportFormat=brief"
  "-Dsurefire.printSummary=true"
)
if [[ -n "${OBSINITY_SUREFIRE_ARGS:-}" ]]; then
  read -r -a SUREFIRE_ARGS <<< "${OBSINITY_SUREFIRE_ARGS}"
fi
(cd "${E2E_DIR}" && mvn ${MAVEN_QUIET_FLAG} "${SUREFIRE_ARGS[@]}" -Dtest=ApiE2EJUnitTest,Junit4SmokeTest test)

#!/bin/bash
set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PROFILE="${PROFILE:-local}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-$PROFILE}"
IMAGE_NAME="${IMAGE_NAME:-obsinity-demo-client}"
CONTAINER_NAME="${CONTAINER_NAME:-obsinity-demo-client}"
PORT="${PORT:-8080}"
SERVICE_NAME="${OBSINITY_SERVICE:-obsinity-demo-client}"

CLEAN_START=false
if [[ $# -gt 0 && "$1" == "--clean" ]]; then
  CLEAN_START=true
  shift
fi

EXTRA_ARGS=("$@")

function stop_container() {
  if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "🛑 Stopping existing container (${CONTAINER_NAME})..."
    docker rm -f "${CONTAINER_NAME}" >/dev/null
  fi
}

if [[ "$CLEAN_START" == true ]]; then
  echo "🧨 Clean start requested (profile: ${SPRING_PROFILES_ACTIVE})..."
  stop_container
  echo "🔄 Rebuilding image before start..."
  (cd "$APP_DIR" && PROFILE="$PROFILE" IMAGE_NAME="$IMAGE_NAME" CONTAINER_NAME="$CONTAINER_NAME" ./build.sh)
else
  stop_container
  if ! docker image inspect "${IMAGE_NAME}" >/dev/null 2>&1; then
    echo "❌ Docker image '${IMAGE_NAME}' not found. Run ./build.sh first or use --clean." >&2
    exit 1
  fi
fi

echo "🚀 Starting Obsinity demo container (profile: ${SPRING_PROFILES_ACTIVE}, port: ${PORT})..."
docker run -d \
  --name "${CONTAINER_NAME}" \
  -p "${PORT}:8080" \
  --add-host host.docker.internal:host-gateway \
  -e SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE}" \
  -e OBSINITY_SERVICE="${SERVICE_NAME}" \
  "${EXTRA_ARGS[@]}" \
  "${IMAGE_NAME}"

echo "📜 Tailing logs (Ctrl+C to stop)..."
docker logs -f "${CONTAINER_NAME}"

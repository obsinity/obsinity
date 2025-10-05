#!/bin/bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$APP_DIR"/.. && pwd)"

PROFILE="${PROFILE:-local}"
IMAGE_NAME="${IMAGE_NAME:-obsinity-demo-client}"
CONTAINER_NAME="${CONTAINER_NAME:-obsinity-demo-client}"

function build_maven() {
  echo "🔄 Cleaning and building the reference client demo (profile: ${PROFILE})..."
  (cd "$REPO_ROOT" && mvn -U -P "${PROFILE}" spotless:apply clean install -DskipTests -pl obsinity-reference-client-spring -am)
}

function stop_running_container() {
  if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "🛑 Stopping existing container (${CONTAINER_NAME})..."
    docker rm -f "${CONTAINER_NAME}" >/dev/null
  fi
}

function remove_old_image() {
  if docker image inspect "${IMAGE_NAME}" >/dev/null 2>&1; then
    echo "🧹 Removing old image (${IMAGE_NAME})..."
    docker image rm -f "${IMAGE_NAME}" >/dev/null
  fi
}

function build_docker_image() {
  echo "🐳 Building Docker image (${IMAGE_NAME})..."
  (cd "$REPO_ROOT" && docker build -f "$APP_DIR/Dockerfile" -t "${IMAGE_NAME}" .)
}

build_maven
stop_running_container
remove_old_image
build_docker_image

echo "✅ Build complete. Use ./run.sh to start the container."

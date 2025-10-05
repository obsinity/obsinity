#!/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker-compose.demo.yml"
PROFILE="${PROFILE:-local}"

function build_maven() {
  echo "🔄 Building Obsinity demo stack modules (profile: ${PROFILE})..."
  (cd "$REPO_ROOT" && mvn -U -P "${PROFILE}" clean install -DskipTests -pl obsinity-reference-service,obsinity-reference-client-spring -am)
}

function stop_stack() {
  echo "🛑 Stopping existing demo stack containers (if any)..."
  (cd "$REPO_ROOT" && docker compose -f "$COMPOSE_FILE" down --remove-orphans >/dev/null 2>&1 || true)
}

function build_images() {
  echo "🐳 Rebuilding demo stack images..."
  (cd "$REPO_ROOT" && docker compose -f "$COMPOSE_FILE" build --no-cache)
}

build_maven
stop_stack
build_images

echo "✅ Demo stack build complete. Run ./run-demo-stack.sh [--clean] to start it."

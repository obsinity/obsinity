#!/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker-compose.demo.yml"
PROFILE="${PROFILE:-local}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-$PROFILE}"

if [[ "${1:-}" == "--clean" ]]; then
  echo "🧨 Clean start requested (profile: ${SPRING_PROFILES_ACTIVE})..."
  (cd "$REPO_ROOT" && docker compose -f "$COMPOSE_FILE" down -v --remove-orphans || true)
  docker volume rm -f obsinity_pg_demo >/dev/null 2>&1 || true
  echo "🔄 Rebuilding containers..."
  (cd "$REPO_ROOT" && docker compose -f "$COMPOSE_FILE" up -d --build)
else
  echo "🚀 Starting Obsinity demo stack (profile: ${SPRING_PROFILES_ACTIVE})..."
  (cd "$REPO_ROOT" && docker compose -f "$COMPOSE_FILE" up -d)
fi

echo "📜 Tailing logs (Ctrl+C to stop)..."
(cd "$REPO_ROOT" && docker compose -f "$COMPOSE_FILE" logs -f)

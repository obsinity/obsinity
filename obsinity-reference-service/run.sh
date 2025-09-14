#!/bin/bash
set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Select Spring profile from PROFILE env (defaults to 'local').
# Respect an explicitly provided SPRING_PROFILES_ACTIVE.
PROFILE="${PROFILE:-local}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-$PROFILE}"

if [[ "$1" == "--clean" ]]; then
  echo "🧨 Stopping stack and wiping database volume (profile: ${SPRING_PROFILES_ACTIVE})..."
  (cd "$APP_DIR" && docker compose down -v)

  # Explicitly remove named Postgres volume if it still exists
  docker volume rm -f obsinity-reference-service_obsinity_pg || true

  echo "🔄 Rebuilding image(s) and starting fresh stack (SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE})..."
  (cd "$APP_DIR" && SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE" docker compose --env-file .env up -d --build)
else
  echo "🚀 Starting Obsinity Reference Service (profile: ${SPRING_PROFILES_ACTIVE}, HTTP port 8086)..."
  (cd "$APP_DIR" && SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE" docker compose --env-file .env up -d)
fi

echo "📜 Tailing logs..."
(cd "$APP_DIR" && docker compose logs -f)

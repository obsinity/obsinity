#!/bin/bash
set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "$1" == "--clean" ]]; then
  echo "🧨 Stopping stack and wiping database volume..."
  (cd "$APP_DIR" && docker compose down -v)

  # Explicitly remove named Postgres volume if it still exists
  docker volume rm -f obsinity-reference-service_obsinity_pg || true

  echo "🔄 Rebuilding image(s) and starting fresh stack..."
  (cd "$APP_DIR" && docker compose --env-file .env up -d --build)
else
  echo "🚀 Starting Obsinity Reference Service (HTTP only, port 8086)..."
  (cd "$APP_DIR" && docker compose --env-file .env up -d)
fi

echo "📜 Tailing logs..."
(cd "$APP_DIR" && docker compose logs -f)

#!/bin/bash
set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "🧨 Rebuilding image(s) and starting fresh stack..."
(cd "$APP_DIR" && docker compose down && docker compose --env-file .env up --build)

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

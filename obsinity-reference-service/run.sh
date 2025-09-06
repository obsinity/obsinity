#!/bin/bash
set -e
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "🚀 Starting Obsinity Reference Service (HTTP only, port 8086)..."
(cd "$APP_DIR" && docker compose --env-file .env up -d && docker compose logs -f)

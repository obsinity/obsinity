#!/bin/bash
set -e

CONTAINER_NAME="${1:-obsinity-db}"

echo "🐘 Connecting to Postgres shell (container: ${CONTAINER_NAME}, db/user: obsinity)..."
docker exec -it "${CONTAINER_NAME}" psql -U obsinity -d obsinity

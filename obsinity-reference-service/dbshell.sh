#!/bin/bash
set -e

echo "🐘 Connecting to Postgres shell (container: obsinity-db, db/user: obsinity)..."
docker exec -it obsinity-db psql -U obsinity -d obsinity

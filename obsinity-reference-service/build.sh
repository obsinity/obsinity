#!/bin/bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$APP_DIR"/.. && pwd)"

function build_maven() {
  echo "🔄 Cleaning and building the reference service..."
  (cd "$REPO_ROOT" && mvn -U spotless:apply clean install -DskipTests -pl obsinity-reference-service -am)
}

function stop_docker() {
  echo "🛑 Stopping and removing existing containers..."
  (cd "$APP_DIR" && docker compose down || true)
}

function remove_old_image() {
  echo "🧹 Removing old image (if any)..."
  docker image prune -f >/dev/null 2>&1 || true
}

function build_docker_image() {
  echo "🐳 Building Docker image for reference service..."
  (cd "$APP_DIR" && docker compose build app)
}

build_maven
stop_docker
remove_old_image
build_docker_image
echo "✅ Build complete. Use ./run.sh to start."

#!/bin/bash

set -e

CLEAN_MODE=false
for arg in "$@"; do
    if [[ "$arg" == "--clean" ]]; then
        CLEAN_MODE=true
        break
    fi
done

echo "=========================================="
echo "Obsinity Grafana Demo Stack"
echo "=========================================="
echo ""
echo "Usage:"
echo "  $0 [--clean]"
echo "    --clean  Remove database volumes for a fresh start"
echo ""

# Full rebuild to ensure the latest code is packaged into Docker images.
echo "Running full rebuild (mvn clean verify)..."
(cd "$(dirname "$0")" && mvn clean verify)

# Choose docker compose command (v2 preferred, v1 fallback)
if command -v docker &> /dev/null && docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
else
    echo "ERROR: docker compose (v2) or docker-compose (v1) is required"
    exit 1
fi

echo "Stopping existing Obsinity containers..."
if $CLEAN_MODE; then
    echo "Clean mode enabled: removing volumes"
    (cd obsinity-reference-service && ${COMPOSE_CMD} down -v --remove-orphans 2>/dev/null) || true
    ${COMPOSE_CMD} -f docker-compose.demo.yml down -v --remove-orphans || true

    # Explicitly remove named database volumes to guarantee a fresh start.
    if command -v docker &> /dev/null; then
        for VOLUME in obsinity_pg_demo obsinity_pg; do
            if docker volume inspect "${VOLUME}" >/dev/null 2>&1; then
                docker volume rm -f "${VOLUME}" >/dev/null 2>&1 || true
                echo "✓ Removed volume ${VOLUME}"
            fi
        done
    fi
else
    (cd obsinity-reference-service && ${COMPOSE_CMD} down --remove-orphans 2>/dev/null) || true
    ${COMPOSE_CMD} -f docker-compose.demo.yml down --remove-orphans || true
fi

echo "✓ Existing containers stopped"

echo ""
echo "Starting demo stack (fresh)..."
${COMPOSE_CMD} -f docker-compose.demo.yml up -d --force-recreate --renew-anon-volumes --build

echo ""
echo "Waiting for services to be ready..."
sleep 10

# Wait for Obsinity server
echo -n "Checking Obsinity server..."
for i in {1..30}; do
    if curl -s http://localhost:8086/actuator/health > /dev/null 2>&1; then
        echo " ✓"
        break
    fi
    if [ $i -eq 30 ]; then
        echo " ✗"
        echo "WARNING: Obsinity server may not be ready"
    fi
    sleep 2
done

# Wait for Grafana
echo -n "Checking Grafana..."
for i in {1..30}; do
    if curl -s http://localhost:3086/api/health > /dev/null 2>&1; then
        echo " ✓"
        break
    fi
    if [ $i -eq 30 ]; then
        echo " ✗"
        echo "WARNING: Grafana may not be ready"
    fi
    sleep 2
done

echo ""
echo "=========================================="
echo "Demo stack is ready!"
echo "=========================================="
echo ""
echo "Services:"
echo "  - Obsinity Server: http://localhost:8086"
echo "  - Grafana:         http://localhost:3086 (admin/admin)"
echo "  - Demo Client:     http://localhost:8080"
echo "  - PostgreSQL:      localhost:5432 (obsinity/obsinity)"
echo ""
echo "Dashboards:"
echo "  - Obsinity Demo - Overview"
echo ""
echo "To generate more data:"
echo "  curl -X POST http://localhost:8086/internal/demo/generate-unified-events \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"events\": 5000, \"recentWindowSeconds\": 7200}'"
echo ""
echo "To stop:"
echo "  ${COMPOSE_CMD} -f docker-compose.demo.yml down"
echo ""

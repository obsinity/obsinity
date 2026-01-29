#!/bin/bash

set -e

echo "=========================================="
echo "Obsinity Grafana Demo Stack"
echo "=========================================="
echo ""

# Choose docker compose command (v2 preferred, v1 fallback)
if command -v docker &> /dev/null && docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
else
    echo "ERROR: docker compose (v2) or docker-compose (v1) is required"
    exit 1
fi

# Stop any existing Obsinity containers to avoid conflicts
echo "Checking for existing containers..."
if docker ps -a --format "{{.Names}}" | grep -q -E "obsinity-reference-server|obsinity-db"; then
    echo "⚠️  Found existing development containers. Stopping them..."
    (cd obsinity-reference-service && docker compose down 2>/dev/null) || true
    echo "✓ Existing containers stopped"
fi

echo ""
echo "Starting demo stack..."
${COMPOSE_CMD} -f docker-compose.demo.yml up -d

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
    if curl -s http://localhost:3000/api/health > /dev/null 2>&1; then
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
echo "Generating demo data..."
curl -X POST http://localhost:8086/internal/demo/generate-unified-events \
  -H "Content-Type: application/json" \
  -d '{
    "serviceKey": "payments",
    "eventType": "user_profile.updated",
    "events": 1000,
    "profilePool": 100,
    "statuses": ["NEW", "ACTIVE", "SUSPENDED", "BLOCKED", "UPGRADED", "ARCHIVED"],
    "channels": ["web", "mobile", "partner"],
    "regions": ["us-east", "us-west", "eu-central"],
    "tiers": ["FREE", "PLUS", "PRO"],
    "maxDurationMillis": 1500,
    "recentWindowSeconds": 3600
  }' 2>/dev/null

echo ""
echo "=========================================="
echo "Demo stack is ready!"
echo "=========================================="
echo ""
echo "Services:"
echo "  - Obsinity Server: http://localhost:8086"
echo "  - Grafana:         http://localhost:3000 (admin/admin)"
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

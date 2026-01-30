#!/bin/bash

echo "=========================================="
echo "Stopping Existing Obsinity Containers"
echo "=========================================="
echo ""

# Function to stop containers from the reference service
stop_reference_service() {
    if [ -f "obsinity-reference-service/docker-compose.yml" ]; then
        echo "🛑 Stopping reference service containers..."
        (cd obsinity-reference-service && docker compose down 2>/dev/null || true)
    fi
}

# Function to stop demo stack
stop_demo_stack() {
    if [ -f "docker-compose.demo.yml" ]; then
        echo "🛑 Stopping demo stack containers..."
        docker-compose -f docker-compose.demo.yml down 2>/dev/null || true
    fi
}

# Function to stop any Obsinity containers by name
stop_by_name() {
    echo "🔍 Checking for any remaining Obsinity containers..."

    # Get all container IDs with obsinity in the name
    CONTAINERS=$(docker ps -a --format "{{.ID}} {{.Names}}" | grep -i obsinity | awk '{print $1}')

    if [ -n "$CONTAINERS" ]; then
        echo "Found containers to stop:"
        docker ps -a --format "table {{.Names}}\t{{.Status}}" | grep -i obsinity
        echo ""
        echo "Stopping and removing..."
        echo "$CONTAINERS" | xargs docker rm -f 2>/dev/null || true
    else
        echo "✓ No Obsinity containers found"
    fi
}

# Execute cleanup
stop_reference_service
stop_demo_stack
stop_by_name

echo ""
echo "=========================================="
echo "✅ Cleanup Complete"
echo "=========================================="
echo ""
echo "You can now start the demo stack:"
echo "  ./start-grafana-demo.sh"
echo ""
echo "Or the reference service:"
echo "  cd obsinity-reference-service && ./run.sh"
echo ""

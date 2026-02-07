# Reference Service helper scripts

Usage:
~~~bash
cd obsinity-reference-service
./build.sh
./run.sh [--clean]
./dbshell.sh
~~~

## Grafana Integration

The reference service includes Grafana dashboards for visualizing demo data. When running the demo stack, Grafana is available at http://localhost:3086 (admin/admin).

See [grafana/README.md](grafana/README.md) for complete documentation on:
- Dashboard panels and visualizations
- API query examples
- Demo data generation
- Customization guide

Quick start:
```bash
# Start the demo stack with Grafana
docker-compose -f docker-compose.demo.yml up -d

# Start background demo generation
curl -X POST http://localhost:8086/internal/demo/generate-unified-events \
  -H "Content-Type: application/json" \
  -d '{"duration": "2m", "eventsPerSecond": 500, "events": 60000, "recentWindow": "1h", "recentWindowSeconds": 10800, "runIntervalSeconds": 60}'

Note: demo generation is real-time; timestamps are clustered around "now".

curl http://localhost:8086/internal/demo/generate-unified-events/status

curl -X POST http://localhost:8086/internal/demo/generate-unified-events/stop

# Open Grafana
open http://localhost:3086
```

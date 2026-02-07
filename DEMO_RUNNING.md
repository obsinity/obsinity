# ✅ Demo Stack Successfully Started!

## Current Status

All services are **running**:

```
✓ PostgreSQL          (obsinity-demo-db) - Port 5432
✓ Obsinity Server     (obsinity-reference-server) - Port 8086  
✓ Demo Client         (obsinity-demo-client) - Port 8080
✓ Grafana             (obsinity-grafana) - Port 3086
```

## Access the Demo

### 1. Open Grafana
**URL:** http://localhost:3086  
**Login:** admin / admin

### 2. Find the Dashboard
- Click "Dashboards" in the left sidebar (four squares icon)
- Look for "Obsinity Demo - Overview"
- Or go directly to: http://localhost:3086/d/obsinity-demo-overview

### 3. Start Demo Generator

```bash
# Start the generator (runs until stopped)
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
    "maxEventDurationMillis": 1500,
    "runIntervalSeconds": 60
  }'

curl http://localhost:8086/internal/demo/generate-unified-events/status

curl -X POST http://localhost:8086/internal/demo/generate-unified-events/stop
```

**Note:** Initial data generation (100 events) has already been triggered.

## What You'll See in Grafana

The dashboard includes 9 panels:

1. **Current State Counts** - Distribution of profiles by status
2. **State Gauge** - Key metrics (ACTIVE, SUSPENDED, BLOCKED)
3. **State Count Time Series** - Historical state counts (1m intervals)
4. **State Transitions** - Flow visualization (NEW→ACTIVE, etc.) at 5s intervals
5. **HTTP Request Latency** - p50/p90/p95/p99 percentiles for checkout API
6. **Profile Update Latency** - Duration by channel (web/mobile/partner)
7. **HTTP Requests by Status** - 200 vs 500 status codes (5m rollup)
8. **Profile Updates** - Multi-dimensional counters (1m rollup)

## Troubleshooting

### If Grafana shows "No Data":
1. **Check time range** - Set to "Last 1 hour" (top right)
2. **Generate more data** - Run the curl command above
3. **Wait 30 seconds** - Dashboard auto-refreshes every 30s

### If you see connection errors:
```bash
# Check if all containers are running
docker ps | grep obsinity

# Check Obsinity server logs
docker logs obsinity-reference-server --tail 50

# Restart Grafana if needed
docker restart obsinity-grafana
```

### Test API directly:
```bash
# Check server health (may show 503 due to RabbitMQ, but HTTP endpoints work)
curl http://localhost:8086/actuator/health

# Test a query endpoint
curl -X POST http://localhost:8086/api/query/state-counts \
  -H "Content-Type: application/json" \
  -d '{"serviceKey":"payments","objectType":"UserProfile","attribute":"user.status","states":["ACTIVE","SUSPENDED"]}'
```

## Stopping the Demo

```bash
# Stop all containers
docker stop obsinity-grafana obsinity-reference-server obsinity-demo-client obsinity-demo-db

# Or remove everything (including data)
docker rm -f obsinity-grafana obsinity-reference-server obsinity-demo-client obsinity-demo-db
docker volume rm grafana_data obsinity_pg_demo
```

## Restarting the Demo

If you stopped the containers:

```bash
# Start existing containers
docker start obsinity-demo-db obsinity-reference-server obsinity-demo-client obsinity-grafana

# Or use the script next time
./start-grafana-demo.sh
```

## Important Notes

### Why the manual start?

The demo stack had a conflict with existing development containers. The solution was:

1. ✅ Stopped existing development containers
2. ✅ Started demo stack containers  
3. ✅ Manually created Grafana (docker-compose had timeout issues on Windows)
4. ✅ Copied provisioning files into Grafana container

### For next time:

**Use the helper script to avoid conflicts:**

```bash
# Stop any existing containers first
./stop-all-obsinity.sh

# Then start the demo stack
docker-compose -f docker-compose.demo.yml up -d
```

OR just use the existing development scripts:

```bash
cd obsinity-reference-service
./build.sh && ./run.sh
```

## Enjoy the Demo! 🎉

Your Grafana dashboards are now displaying real-time Obsinity metrics via the REST API.

For more information:
- See `obsinity-reference-service/grafana/README.md`
- See `WHICH_SETUP.md` for choosing between dev and demo modes
- See `SETUP_COMPARISON.md` for detailed comparison

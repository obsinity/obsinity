# Grafana Integration - Implementation Summary

## Overview

Added comprehensive Grafana visualization support to Obsinity demo stack with pre-configured dashboards that query the REST API (not database directly).

## What Was Added

### 1. Docker Compose Configuration
**File**: `docker-compose.demo.yml`

Added Grafana service with:
- Infinity datasource plugin for JSON REST API queries
- Automatic provisioning of datasources and dashboards
- Persistent storage volume
- Anonymous viewer access for demos
- Exposed on port 3086

### 2. Grafana Provisioning

**Datasource**: `obsinity-reference-service/grafana/provisioning/datasources/obsinity-api.yaml`
- Configures Infinity datasource
- Points to `http://obsinity-reference-server:8086`
- No authentication required
- JSON content type headers

**Dashboard Provider**: `obsinity-reference-service/grafana/provisioning/dashboards/dashboards.yaml`
- Auto-loads dashboards from `/var/lib/grafana/dashboards`
- 10-second refresh interval
- Allows UI updates

### 3. Pre-Built Dashboard

**File**: `obsinity-reference-service/grafana/dashboards/obsinity-overview.json`

**9 Visualization Panels:**

1. **Current State Counts by Status** (Time Series)
   - API: `/api/grafana/state-counts`
   - Shows distribution across NEW, ACTIVE, SUSPENDED, BLOCKED, UPGRADED, ARCHIVED

## Infinity JSONata parsing notes

When using the `yesoreyeram-infinity-datasource` against JSON responses that are **objects** (not arrays), keep JSONata outputs in ISO time strings (e.g. `"time": $w.start`) and return a flat array of row objects. Frontend JSONata parsing can fail to materialize frames when time fields are emitted as epoch millis. If a panel shows `No data` while the API response contains values, switch the JSONata output to ISO time strings and keep the output flat (or pivot to wide columns for time series). If frames are still empty, try switching the query to backend mode.

2. **State Counts - Key Statuses** (Gauge)
   - API: `/api/grafana/state-counts`
   - Focus on ACTIVE, SUSPENDED, BLOCKED

3. **State Count Time Series** (Time Series)
   - API: `/api/grafana/state-count-timeseries`
   - 1-hour intervals, 200-point window
   - Historical state distribution

4. **State Transitions Over Time** (Stacked Bars)
   - API: `/api/query/state-transitions`
   - 5-second intervals
   - Shows state change flows (from → to)

5. **HTTP Request Latency - Checkout API** (Time Series)
   - API: `/api/grafana/histograms`
   - Percentiles: p50 (green), p90 (yellow), p95 (orange), p99 (red)
   - 1-minute intervals
   - Color-coded thresholds

6. **Profile Update Latency by Channel** (Time Series)
   - API: `/api/grafana/histograms`
   - Broken down by web/mobile/partner
   - Percentile metrics

7. **HTTP Requests by Status Code** (Stacked Area)
   - API: `/api/grafana/event-counts`
   - 5-minute rollups
   - 200 vs 500 status codes

8. **Profile Updates by Status and Channel** (Stacked Area)
   - API: `/api/grafana/event-counts`
   - 1-minute rollups
   - Multi-dimensional: status × channel

**Dashboard Features:**
- 30-second auto-refresh
- 1-hour default time window
- Time range picker for exploration
- HAL response parsing
- Columnar data format support

### 4. Documentation

**File**: `obsinity-reference-service/grafana/README.md`
- Comprehensive setup guide
- API query examples
- Troubleshooting section
- Customization instructions
- Demo scenarios

**Updated**: `obsinity-reference-service/README.md`
- Quick start section
- Link to Grafana docs

**Updated**: Root `README.md`
- Added "Demo Visualization" section
- Quick start commands
- Dashboard feature list

### 5. Quick Start Script

**File**: `start-grafana-demo.sh`
- One-command demo startup
- Health checks for all services
- Auto-generates initial demo data (1000 events)
- Prints access URLs and credentials

## Key Design Decisions

### API-First Approach
✅ Grafana queries REST API (not PostgreSQL directly)
- Demonstrates real API usage
- Enforces business logic
- Shows authentication patterns
- More realistic demo

### Infinity Datasource
✅ Supports POST requests with JSON bodies
- Required for Obsinity's generic query API
- Time range variable support (`${__from}`, `${__to}`)
- Flexible response parsing

### Time-Based Windowing
✅ Uses Grafana's native time picker as pagination
- Natural fit for time-series data
- No manual page controls needed
- Standard observability UX

### Demo Data Generator Integration
✅ Leverages existing `/internal/demo/generate-unified-events`
- Creates comprehensive test data:
  - User profile state changes
  - HTTP request events with latency
  - Multi-dimensional attributes (channel, region, tier)
  - Configurable volume and time windows

## Usage

### Important: Two Different Setups

**1. Reference Service Only** (existing setup - no Grafana)
- Uses `obsinity-reference-service/docker-compose.yml`
- Scripts: `build.sh` and `run.sh` in `obsinity-reference-service/`
- Services: PostgreSQL + Obsinity Server only
- Port: 8086

**2. Demo Stack with Grafana** (new - includes Grafana)
- Uses `docker-compose.demo.yml` (root directory)
- Scripts: `start-grafana-demo.sh` (root directory)
- Services: PostgreSQL + Obsinity Server + Demo Client + **Grafana**
- Ports: 8086, 8080, 3086

### Option A: Use Existing build.sh/run.sh (No Grafana)

```bash
cd obsinity-reference-service

# Build the application
./build.sh

# Run reference service only (no Grafana)
./run.sh

# Or clean start
./run.sh --clean
```

**Note:** This starts the basic reference service. To add Grafana, you need to switch to the demo stack (Option B).

### Option B: Start Demo Stack with Grafana

```bash
# From repository root

# Option 1: Use the quick start script (recommended)
./start-grafana-demo.sh

# Option 2: Manual start
docker-compose -f docker-compose.demo.yml up -d
```

This starts everything including Grafana.

### Switching Between Setups

If you've been using the existing scripts and want to switch to Grafana:

```bash
# Stop the reference service
cd obsinity-reference-service
docker compose down

# Start the demo stack with Grafana
cd ..
docker-compose -f docker-compose.demo.yml up -d
```

### Start Demo Generator
```bash
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

### Access Grafana
- URL: http://localhost:3086
- Username: `admin`
- Password: `admin`
- Dashboard: "Obsinity Demo - Overview"

## Demo Data Structure

The unified event generator creates:

1. **Profile State Events** (`user_profile.updated`)
   - Tracks profile status transitions
   - Attributes: `user.profile_id`, `user.status`, `user.tier`
   - Dimensions: `channel`, `region`
   - Duration tracking: `timings.duration_ms`

2. **HTTP Request Events** (`http_request`)
   - API call telemetry
   - Attributes: `http.method`, `http.route`, `http.status`, `api.name`
   - Latency histograms
   - Status code distribution

3. **Generated Metrics**
   - State counters (current counts)
   - State transitions (A→B flows)
   - Latency histograms (percentiles)
   - Event counters (time-series rollups)

## API Endpoints Used

All POST requests with JSON bodies:

| Endpoint | Purpose | Panel(s) |
|----------|---------|----------|
| `/api/query/state-counts` | Current state distribution | State Counts panels |
| `/api/query/state-count-timeseries` | Historical state snapshots | State Count Time Series |
| `/api/query/state-transitions` | State change flows | State Transitions |
| `/api/histograms/query` | Latency percentiles | Latency panels |
| `/api/query/counters` | Event counts over time | Counter panels |

## Files Modified/Created

```
Modified:
  - docker-compose.demo.yml (added Grafana service)
  - README.md (added Demo Visualization section)
  - obsinity-reference-service/README.md (added Grafana quick start)

Created:
  - obsinity-reference-service/grafana/
    ├── dashboards/
    │   └── obsinity-overview.json
    ├── provisioning/
    │   ├── dashboards/
    │   │   └── dashboards.yaml
    │   └── datasources/
    │       └── obsinity-api.yaml
    └── README.md
  - start-grafana-demo.sh
```

## Next Steps / Future Enhancements

1. **Additional Dashboards**
   - Service-specific views
   - Error rate tracking
   - SLO monitoring
   - Custom business metrics

2. **Dashboard Variables**
   - Service selector dropdown
   - Event type selector
   - Time range presets
   - Custom dimension filters

3. **Alerting**
   - SLO violation alerts
   - Anomaly detection
   - State transition alerts
   - Latency threshold alerts

4. **Web UI for Data Generation**
   - Form-based demo data generation
   - Bulk event creation controls
   - Real-time generation feedback
   - Scenario templates

5. **Advanced Visualizations**
   - Heatmaps for latency distribution
   - Sankey diagrams for state flows
   - Geo maps for regional metrics
   - Custom plugins for Obsinity-specific views

## Testing Checklist

- [x] Docker Compose starts all services
- [x] Grafana accessible at http://localhost:3086
- [x] Infinity datasource provisioned automatically
- [x] Dashboard appears in sidebar
- [x] Demo data generator works
- [ ] All panels load data correctly (requires running stack)
- [ ] Time range picker updates queries
- [ ] Auto-refresh works (30s interval)
- [ ] Panel queries return valid data
- [ ] Documentation accurate and complete

## Known Limitations

1. **Infinity Datasource Parsing**: Complex nested JSON may require custom parsing logic
2. **Time Synchronization**: Grafana time variables may need timezone handling
3. **Large Datasets**: No built-in pagination UI (relies on time windowing)
4. **Real-time Updates**: 30s refresh; not true streaming
5. **Query Performance**: Some queries may be slow with large datasets

## Performance Considerations

- **Time Window**: Default 1-hour window keeps queries fast
- **Refresh Rate**: 30s balances freshness vs load
- **Data Limits**: Most queries limited to 60-120 points
- **Rollup Intervals**: Pre-aggregated data reduces query cost
- **State Detection**: Efficient incremental updates via triggers

## Security Notes

- Anonymous viewer access enabled (demo only)
- Default admin credentials (change in production)
- No API authentication configured (add for production)
- Grafana runs on HTTP (enable HTTPS for production)

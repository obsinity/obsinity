# Obsinity Grafana Integration

This directory contains Grafana dashboards and provisioning configuration for visualizing Obsinity demo data.

## Overview

The Grafana setup provides real-time visualization of:
- **State Counts**: Current object state distribution (user profiles by status)
- **State Transitions**: Time-series view of state changes over time
- **State Count Time Series**: Historical state count snapshots
- **HTTP Request Latency**: Percentile-based latency histograms (p50, p90, p95, p99)
- **Profile Update Latency**: Update duration metrics by channel
- **API Counters**: Request counts by status code, method, and other dimensions

## Quick Start

### 1. Start the Demo Stack

```bash
docker-compose -f docker-compose.demo.yml up -d
```

This will start:
- PostgreSQL database
- Obsinity reference server (port 8086)
- Obsinity demo client (port 8080)
- **Grafana (port 3086)**

### 2. Generate Demo Data

Use the Insomnia collection or curl to generate test data:

```bash
# Generate unified demo events (profile updates + HTTP requests + histograms)
curl -X POST http://localhost:8086/internal/demo/generate-unified-events \
  -H "Content-Type: application/json" \
  -d '{
    "serviceKey": "payments",
    "eventType": "user_profile.updated",
    "duration": "2m",
    "eventsPerSecond": 500,
    "events": 60000,
    "profilePool": 100,
    "statuses": ["NEW", "ACTIVE", "ACTIVE", "ACTIVE", "SUSPENDED", "SUSPENDED", "BLOCKED", "UPGRADED", "ARCHIVED", "ARCHIVED", "ARCHIVED"],
    "channels": ["web", "mobile", "partner"],
    "regions": ["us-east", "us-west", "eu-central"],
    "tiers": ["FREE", "PLUS", "PRO"],
    "maxEventDurationMillis": 1500,
    "recentWindow": "1h",
    "recentWindowSeconds": 10800
  }'
```

### 3. Access Grafana

Open your browser to: **http://localhost:3086**

**Login Credentials:**
- Username: `admin`
- Password: `admin`

The dashboard **"Obsinity Demo - Overview"** will be automatically provisioned and available.

## Architecture

### Data Flow

```
Demo Data Generator → Obsinity API → PostgreSQL
                                    ↓
                          Grafana ← REST API Query
```

**Important**: Grafana queries the Obsinity REST API (not the database directly). This demonstrates:
- API-first observability
- Proper access patterns
- Business logic enforcement
- Real-world API usage

### Datasource Configuration

The Infinity datasource is automatically provisioned with:
- **URL**: `http://obsinity-reference-server:8086`
- **Type**: JSON REST API
- **Method**: POST (for all queries)

## Dashboard Panels

### 1. Current State Counts by Status
**API Endpoint**: `/api/grafana/state-counts`

Shows the current distribution of user profiles across different statuses (NEW, ACTIVE, SUSPENDED, etc.).

### 2. State Count Time Series
**API Endpoint**: `/api/grafana/state-count-timeseries`

Historical view of state counts at 1-minute intervals. Tracks how state distributions change over time.

### 3. State Transitions Over Time
**API Endpoint**: `/api/query/state-transitions`

Visualizes state transition events (e.g., NEW → ACTIVE, ACTIVE → SUSPENDED) at 5-second intervals.

### 4. HTTP Request Latency
**API Endpoint**: `/api/grafana/histograms`

Percentile-based latency visualization for the checkout API:
- **p50** (median) - green
- **p90** - yellow
- **p95** - orange
- **p99** - red

### 5. Profile Update Latency by Channel
**API Endpoint**: `/api/grafana/histograms`

Latency histogram broken down by channel (web, mobile, partner).

### 6. HTTP Requests by Status Code
**API Endpoint**: `/api/grafana/event-counts`

Stacked area chart showing request volume by HTTP status code at 5-minute rollups.

### 7. Profile Updates by Status and Channel
**API Endpoint**: `/api/grafana/event-counts`

Multi-dimensional counter showing profile update events grouped by status and channel at 1-minute intervals.

## Query Examples

All queries use POST requests with JSON bodies. Here are some examples:

### State Counts Query (`/api/grafana/state-counts`)
```json
{
  "serviceKey": "payments",
  "objectType": "UserProfile",
  "attribute": "user.status",
  "states": ["NEW", "ACTIVE", "SUSPENDED", "BLOCKED", "UPGRADED", "ARCHIVED"]
}
```

### Histogram Query (`/api/grafana/histograms`)
```json
{
  "range": { "fromMs": 1769754000000, "toMs": 1769757600000 },
  "intervalMs": 60000,
  "serviceKey": "payments",
  "eventType": "http_request",
  "histogramName": "http_request_latency_ms",
  "filters": {
    "http.method": ["GET"],
    "http.route": ["/api/checkout"]
  },
  "percentiles": [0.5, 0.9, 0.95, 0.99]
}
```

### Event Count Query (`/api/grafana/event-counts`)
```json
{
  "range": { "fromMs": 1769754000000, "toMs": 1769757600000 },
  "bucket": "5m",
  "maxDataPoints": 60,
  "serviceKey": "payments",
  "eventType": "http_request",
  "filters": {
    "http.method": ["GET"],
    "http.status": ["200", "500"]
  }
}
```

## Time Range Selection

Grafana's time picker controls the query time ranges using variables:
- `${__from:date:iso}` - Start time
- `${__to:date:iso}` - End time

The dashboard defaults to "Last 1 hour" with 30-second auto-refresh.

## Customization

### Adding New Panels

1. Click "Add Panel" in Grafana
2. Select "Obsinity API" datasource
3. Configure the query:
   - **Type**: JSON
   - **Method**: POST
   - **URL**: `/api/query/...` (your endpoint)
   - **Data**: JSON query body
   - **Parser**: backend
   - **Root Selector**: (leave empty; responses are arrays)
   - **Columns**: Define the response field mappings

### Modifying Queries

Edit the dashboard JSON file or use the Grafana UI:
- Navigate to the panel
- Click the panel title → Edit
- Modify the query in the "Query" tab
- Save the dashboard

## Troubleshooting

### Dashboard Not Loading
Check that the Obsinity server is running:
```bash
curl http://localhost:8086/api/catalog/event-type
```

### No Data in Panels
1. Verify demo data has been generated
2. Check the time range (default: last 1 hour)
3. Ensure the demo generation covers your time range (`recentWindowSeconds` overrides `recentWindow`)

### Infinity Plugin Not Installed
If you see datasource errors, manually install:
```bash
docker exec -it obsinity-grafana grafana-cli plugins install yesoreyeram-infinity-datasource
docker restart obsinity-grafana
```

## Directory Structure

```
grafana/
├── dashboards/              # Dashboard JSON definitions
│   └── obsinity-overview.json
├── provisioning/
│   ├── dashboards/          # Dashboard provisioning config
│   │   └── dashboards.yaml
│   └── datasources/         # Datasource provisioning config
│       └── obsinity-api.yaml
└── README.md                # This file
```

## API Reference

For detailed API documentation, see the Insomnia collection:
- `obsinity-reference-service/insomnia.yaml`

Key endpoints used in dashboards:
- `/api/query/state-counts` - Current state distribution
- `/api/query/state-count-timeseries` - State count snapshots over time
- `/api/query/state-transitions` - State transition events
- `/api/histograms/query` - Latency percentiles
- `/api/query/counters` - Event counters with time rollups

## Next Steps

1. **Add More Dashboards**: Create focused views for specific services or event types
2. **Add Variables**: Use Grafana variables for dynamic service/event selection
3. **Add Alerts**: Configure alerting rules for SLO violations
4. **Add Annotations**: Mark deployment events on the timeline
5. **Export/Import**: Share dashboard JSON with the team

## Demo Scenarios

### Scenario 1: Profile Update Storm
```bash
# Generate profile updates over 10 minutes
curl -X POST http://localhost:8086/internal/demo/generate-unified-events \
  -H "Content-Type: application/json" \
  -d '{"duration": "10m", "eventsPerSecond": 20, "recentWindow": "10m"}'
```

Watch the state transitions and counters panels update in real-time.

### Scenario 2: Latency Spike Simulation
The demo data generator includes periodic latency spikes (every 50th event). Observe these in the histogram panels as red p99 spikes.

### Scenario 3: Multi-Channel Analysis
Compare profile update latencies across web, mobile, and partner channels using the "Profile Update Latency by Channel" panel.

# Executive Dashboard (Obsinity Demo)

This document describes the executive-facing dashboard, how events are materialized into metrics, and what each panel shows. It is aligned to the Grafana dashboard in `obsinity-reference-service/grafana/dashboards/obsinity-overview.json` and the REST endpoints described in `obsinity-reference-service/grafana/README.md`.

## Purpose

The executive dashboard provides a fast, high-level view of business and operational health for the demo service (`payments`). It emphasizes:
- Customer impact (latency, state distribution)
- Operational stability (state churn, spikes in latency)
- Directional trends (how things move over time)

## How Events Become Metrics

Obsinity processes raw events and materializes metrics during ingestion. The high-level flow is:

1. **Events arrive** via the Obsinity API.
2. **Validation and normalization** enforce schema and consistency.
3. **Events are persisted** to partitioned storage.
4. **Metrics are updated** immediately:
   - **Counters** for event volumes and categorical breakdowns
   - **Histograms** for latency and duration distributions
   - **State counts** for current object states
   - **State transitions** for changes between states
5. **Rollups are materialized** on a fixed ladder to support fast queries:
   - `5s → 1m → 1h → 1d → 7d`
6. **Grafana panels query the Obsinity REST API** (not the DB directly), which selects the correct rollup and returns time-series slices.

### Event Sources in the Demo

- `http_request` events
  - Used for **request latency histograms**
  - Filtered by `http.method` and `http.route`
- `user_profile.updated` events
  - Used for **profile update latency histograms**
  - Used for **state counts** and **state transitions** on `user.status`

## Panel Mapping

Each panel below lists the metric type, source events, endpoint, and executive interpretation.

### 1. HTTP Request Latency - Checkout API (percentiles)
- **Metric type**: Histogram percentiles
- **Source event**: `http_request`
- **Filters**: `http.method = GET`, `http.route = /api/checkout`
- **Endpoint**: `/api/grafana/histograms`
- **What it depicts**: p50 / p90 / p95 / p99 latency over time for checkout traffic.
- **Executive read**: Are critical revenue paths staying fast? Tail latency (p95/p99) is the early warning.

### 2. HTTP Request Latency - Profile API (percentiles)
- **Metric type**: Histogram percentiles
- **Source event**: `user_profile.updated`
- **Filters**: `dimensions.channel = web`, `dimensions.region = us-east`
- **Endpoint**: `/api/grafana/histograms`
- **What it depicts**: p50 / p90 / p99 profile update duration over time.
- **Executive read**: Is customer profile handling responsive in key regions/channels?

### 3. UserProfile State Counts by Status
- **Metric type**: State count snapshot
- **Source event**: `user_profile.updated`
- **Attribute**: `user.status`
- **Endpoint**: `/api/grafana/state-counts`
- **What it depicts**: Current distribution of user profiles across statuses (NEW, ACTIVE, SUSPENDED, BLOCKED, UPGRADED, ARCHIVED).
- **Executive read**: Health of the customer base and churn risk at a glance.

### 4. UserProfile State Counts - Key Statuses
- **Metric type**: State count snapshot
- **Source event**: `user_profile.updated`
- **Attribute**: `user.status`
- **Endpoint**: `/api/grafana/state-counts`
- **What it depicts**: Focused view of `ACTIVE`, `SUSPENDED`, `BLOCKED` counts.
- **Executive read**: Early visibility into growth vs. policy enforcement vs. risk events.

### 5. UserProfile State Count Time Series (15m intervals)
- **Metric type**: State count time series
- **Source event**: `user_profile.updated`
- **Attribute**: `user.status`
- **Endpoint**: `/api/grafana/state-count-timeseries`
- **Bucket**: `15m`
- **What it depicts**: Trend lines for key status populations over time.
- **Executive read**: Whether the business is trending toward healthier activation or accumulating risk/attrition.

### 6. State Transitions Over Time (15m intervals)
- **Metric type**: State transition time series
- **Source event**: `user_profile.updated`
- **Attribute**: `user.status`
- **Endpoint**: `/api/grafana/state-transitions`
- **Bucket**: `15m`
- **What it depicts**: Flow into states (e.g., `(none) → NEW`, `(none) → ACTIVE`, `(none) → SUSPENDED`).
- **Executive read**: Rate of onboarding, conversion, and risk events; a spike in transitions to `SUSPENDED` or `BLOCKED` signals policy or fraud pressure.

## Notes and Assumptions

- All panels are driven by REST API calls, not direct SQL.
- Buckets and rollups are selected automatically based on the query interval.
- The demo generator produces synthetic data clustered around “now,” so the dashboard is oriented around near-real-time signals.

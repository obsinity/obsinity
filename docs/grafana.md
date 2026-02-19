# Grafana Query API (MVP)

This API is Grafana‑specific and returns Grafana‑friendly frames directly. No JSONata/Infinity flattening is required.

## POST /api/grafana/query

Grafana sends time range and interval hints. Obsinity uses:
- `range.fromMs`/`range.toMs` if present (epoch millis, UTC)
- otherwise `range.from`/`range.to` (ISO‑8601)
- `intervalMs` / `maxDataPoints` to pick a bucket when `queries[].bucket` is not provided
- `maxDataPoints` is optional; when omitted, the API defaults to the range point count and caps it at
  `obsinity.grafana.timeseries.max-data-points-cap` (default `1440`)

## Typed Grafana Endpoints

For parity with the standard query API, Obsinity also exposes typed Grafana endpoints. These accept either a
full Grafana payload with `queries[]` or a single-query payload (fields at the root).

- `POST /api/grafana/histograms`
- `POST /api/grafana/state-counts`
- `POST /api/grafana/state-count-timeseries`
- `POST /api/grafana/event-counts`

Example (state count snapshot, single-query payload):

```json
{
  "serviceKey": "payments",
  "objectType": "UserProfile",
  "attribute": "user.status",
  "states": ["ACTIVE", "SUSPENDED", "BLOCKED", "ARCHIVED"]
}
```

### Request (Histogram Percentiles)

```json
{
  "range": { "from": "2026-01-30T09:00:00Z", "to": "2026-01-30T10:00:00Z" },
  "intervalMs": 60000,
  "maxDataPoints": 1000,
  "queries": [
    {
      "refId": "A",
      "kind": "histogram_percentiles",
      "serviceKey": "payments",
      "eventType": "http_request",
      "histogramName": "http_request_latency_ms",
      "filters": {
        "http.method": ["GET"],
        "http.route": ["/api/checkout"]
      },
      "percentiles": [0.5, 0.9, 0.95, 0.99]
    }
  ]
}
```

### Response (Histogram Percentiles)

```json
[
  { "time": "2026-01-30T09:00:00Z", "p50": 120.3, "p90": 145.7, "p95": 150.2, "p99": 162.4 },
  { "time": "2026-01-30T09:01:00Z", "p50": 123.4, "p90": 148.9, "p95": 152.6, "p99": 165.1 }
]
```

### Request (State Count Timeseries)

```json
{
  "range": { "fromMs": 1769754000000, "toMs": 1769757600000 },
  "intervalMs": 3600000,
  "queries": [
    {
      "refId": "B",
      "kind": "state_count",
      "serviceKey": "payments",
      "objectType": "UserProfile",
      "attribute": "user.status",
      "states": ["ACTIVE", "SUSPENDED", "BLOCKED", "ARCHIVED"]
    }
  ]
}
```

### Response (State Count Timeseries)

```json
[
  { "from": "2026-01-30T09:00:00Z", "to": "2026-01-30T10:00:00Z", "state": "ACTIVE", "count": 2007 },
  { "from": "2026-01-30T09:00:00Z", "to": "2026-01-30T10:00:00Z", "state": "SUSPENDED", "count": 120 }
]
```

### Request (State Count Snapshot)

```json
{
  "queries": [
    {
      "refId": "D",
      "kind": "state_count_snapshot",
      "serviceKey": "payments",
      "objectType": "UserProfile",
      "attribute": "user.status",
      "states": ["ACTIVE", "SUSPENDED", "BLOCKED", "ARCHIVED"]
    }
  ]
}
```

### Response (State Count Snapshot)

```json
[
  { "state": "ACTIVE", "count": 2007 },
  { "state": "SUSPENDED", "count": 120 },
  { "state": "BLOCKED", "count": 34 },
  { "state": "ARCHIVED", "count": 18 }
]
```

### Request (Event Count)

```json
{
  "range": { "from": "2026-01-30T09:00:00Z", "to": "2026-01-30T10:00:00Z" },
  "maxDataPoints": 120,
  "queries": [
    {
      "refId": "C",
      "kind": "event_count",
      "serviceKey": "payments",
      "eventType": "http_request",
      "filters": { "http.method": ["GET"] }
    }
  ]
}
```

### Response (Event Count)

```json
[
  { "from": "2026-01-30T09:00:00Z", "to": "2026-01-30T09:01:00Z", "http.method": "GET", "count": 120 },
  { "from": "2026-01-30T09:01:00Z", "to": "2026-01-30T09:02:00Z", "http.method": "GET", "count": 132 }
]
```

## GET /api/grafana/label-values

Used by Grafana template variables.

Example:

```
GET /api/grafana/label-values?label=http.route&serviceKey=payments&from=2026-01-30T09:00:00Z&to=2026-01-30T10:00:00Z
```

Response:

```json
["/api/checkout", "/api/profile"]
```

## Notes

- Time values in responses are ISO‑8601 UTC strings.
- Frame values are columnar arrays aligned to the `fields` order.
- Bucketing uses `queries[].bucket` if provided; otherwise `intervalMs` or `maxDataPoints` drives bucket selection.
- Timeseries responses never exceed the configured cap (`obsinity.grafana.timeseries.max-data-points-cap`, default `1440`).
- Missing state-count 1m snapshots are omitted from the response instead of being synthesized as zeros.

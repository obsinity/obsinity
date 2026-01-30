# Grafana Query API (MVP)

This API is Grafana‑specific and returns Grafana‑friendly frames directly. No JSONata/Infinity flattening is required.

## POST /api/grafana/query

Grafana sends time range and interval hints. Obsinity uses:
- `range.fromMs`/`range.toMs` if present (epoch millis, UTC)
- otherwise `range.from`/`range.to` (ISO‑8601)
- `intervalMs` / `maxDataPoints` to pick a bucket when `queries[].bucket` is not provided

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
{
  "results": [
    {
      "refId": "A",
      "frames": [
        {
          "name": "http_request_latency_ms.p95",
          "fields": [
            { "name": "time", "type": "time" },
            {
              "name": "value",
              "type": "number",
              "labels": {
                "http.method": "GET",
                "http.route": "/api/checkout",
                "percentile": "p95"
              }
            }
          ],
          "values": [
            ["2026-01-30T09:00:00Z", 123.4],
            ["2026-01-30T09:01:00Z", 150.2]
          ]
        }
      ]
    }
  ]
}
```

### Request (State Count)

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

### Response (State Count)

```json
{
  "results": [
    {
      "refId": "B",
      "frames": [
        {
          "name": "user.status.ACTIVE",
          "fields": [
            { "name": "time", "type": "time" },
            { "name": "count", "type": "number", "labels": { "state": "ACTIVE" } }
          ],
          "values": [
            ["2026-01-30T09:00:00Z", 2007],
            ["2026-01-30T10:00:00Z", 2011]
          ]
        }
      ]
    }
  ]
}
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
{
  "results": [
    {
      "refId": "C",
      "frames": [
        {
          "name": "http_request.count",
          "fields": [
            { "name": "time", "type": "time" },
            { "name": "count", "type": "number", "labels": { "http.method": "GET" } }
          ],
          "values": [
            ["2026-01-30T09:00:00Z", 120],
            ["2026-01-30T09:01:00Z", 132]
          ]
        }
      ]
    }
  ]
}
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
- Bucketing uses `queries[].bucket` if provided; otherwise `intervalMs` or `maxDataPoints` drives bucket selection.
- Best effort is made to avoid returning more points than `maxDataPoints`.

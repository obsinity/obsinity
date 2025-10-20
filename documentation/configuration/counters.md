# Counter Configuration Cheatsheet

Obsinity counters now support per-metric ingest granularity. The base bucket controls how often we buffer/flush counts and which rollups get materialised automatically.

## Granularity Options

| `specJson.granularity` | Base Bucket | Flush cadence (default) | Materialised buckets |
| ---------------------- | ----------- | ----------------------- | -------------------- |
| `"5s"` / `"S5"`        | 5 seconds   | 5 seconds               | 5s, 1m, 5m, 1h, 1d, 7d |
| `"1m"` / `"M1"`        | 1 minute    | 60 seconds              | 1m, 5m, 1h, 1d, 7d |
| `"5m"` / `"M5"`        | 5 minutes   | 300 seconds             | 5m, 1h, 1d, 7d |

If the granularity field is omitted we default to `5s`. The value must be one of the table entries; requesting intervals finer than the configured granularity will be rejected by the counter query APIs.

## Sample Event Metric

```yaml
metrics:
  - name: http_requests_total
    type: COUNTER
    keyedKeys:
      - region
      - http.status_code_group
    specJson:
      granularity: "1m"
      filters:
        includeStatus: ["2xx", "5xx"]
```

The ingester will buffer counts in minute buckets, flush once per minute, and roll the deltas into 5-minute, hourly, daily and weekly tables. Existing dashboards can still query the 5s view because the 1m counter never produces finer buckets.

## REST Counter Query

The REST controller exposes the counter query endpoint:

```http
POST /api/counters/query
Content-Type: application/json

{
  "serviceKey": "payments",
  "eventType": "transaction.completed",
  "counterName": "http_requests_total",
  "interval": "5m",
  "start": "2025-01-01T00:00:00Z",
  "end": "2025-01-01T02:00:00Z",
  "key": {
    "region": ["us-east"],
    "http.status_code_group": ["2xx", "5xx"]
  },
  "limits": {
    "offset": 0,
    "limit": 24
  }
}

Response shape:

```json
{
  "count": 1,
  "total": 120,
  "limit": 24,
  "offset": 0,
  "data": {
    "windows": [
      {
        "from": "2025-01-01T00:00:00Z",
        "to": "2025-01-01T00:05:00Z",
        "counts": [
          { "key": { "region": "us-east", "http.status_code_group": "2xx" }, "count": 128 }
        ]
      }
    ]
  },
  "links": {
    "self": {
      "href": "/api/counters/query",
      "method": "POST",
      "body": { "… original request …" }
    },
    "first": {
      "href": "/api/counters/query",
      "method": "POST",
      "body": { "… with offset 0 …" }
    },
    "next": {
      "href": "/api/counters/query",
      "method": "POST",
      "body": { "… with offset 24 …" }
    }
  }
}
```

The payload returns a list of `windows`, each with `from`, `to`, and `counts` for every key combination. Requests finer than the configured granularity (for example `"5s"` against a `5m` counter) still lead to `400 Bad Request`.

## Operational Notes

* Flush cadences can be tuned via configuration keys `obsinity.counters.flush.rate.s5`, `.m1`, `.m5`.
* Hash caches are controlled with `obsinity.counters.hash.cache-size` and `obsinity.counters.hash.ttl`.
* Rollups rely on the hash cache; running the API in a separate process is fine because hashes are deterministic.

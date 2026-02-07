# Counter, Histogram, and State Metric Cheatsheet

Obsinity’s metric pipeline materialises counters, histograms, and state transition counts at ingest time. This page documents the runtime configuration knobs, the REST controllers that query the data, and how service configuration is loaded on the server.

Counters now support per-metric ingest granularity. The base bucket controls how often we buffer/flush counts and which rollups get materialised automatically.

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
POST /api/query/counters
Content-Type: application/json

{
  "serviceKey": "payments",
  "eventType": "user_profile.updated",
  "counterName": "user_profile_updates_by_status",
  "interval": "5m",
  "start": "2025-01-01T00:00:00Z",
  "end": "2025-01-01T02:00:00Z",
  "key": {
    "user.status": ["NEW", "ACTIVE", "SUSPENDED", "ARCHIVED"],
    "dimensions.channel": ["web", "mobile", "partner"]
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
    "intervals": [
      {
        "from": "2025-01-01T00:00:00Z",
        "to": "2025-01-01T00:05:00Z",
        "counts": [
          { "key": { "user.status": "ACTIVE", "dimensions.channel": "web" }, "count": 128 }
        ]
      }
    ]
  },
  "links": {
    "self": {
      "href": "/api/query/counters",
      "method": "POST",
      "body": { "… original request …" }
    },
    "first": {
      "href": "/api/query/counters",
      "method": "POST",
      "body": { "… with offset 0 …" }
    },
    "next": {
      "href": "/api/query/counters",
      "method": "POST",
      "body": { "… with offset 24 …" }
    }
  }
}
```

The payload returns a list of `intervals`, each with `from`, `to`, and `counts` for every key combination. Requests finer than the configured granularity (for example `"5s"` against a `5m` counter) still lead to `400 Bad Request`.

> Demo data tip: `/internal/demo/generate-unified-events` (reference service) emits `user_profile.updated` events cycling through statuses `NEW → ACTIVE → ACTIVE → ACTIVE → SUSPENDED → SUSPENDED → BLOCKED → UPGRADED → ARCHIVED → ARCHIVED → ARCHIVED`, channels `web|mobile|partner`, and regions `us-east|us-west|eu-central`. The counter and histogram examples above target those exact values so you can exercise the APIs with real data immediately.

## REST Histogram Query

Histograms reuse the same controller namespace:

```http
POST /api/histograms/query
Content-Type: application/json

{
  "serviceKey": "payments",
  "eventType": "user_profile.updated",
  "histogramName": "user_profile_update_duration_ms",
  "key": {
    "dimensions.channel": ["web", "mobile"],
    "dimensions.region": ["us-east", "eu-central"]
  },
  "interval": "1m",
  "start": "2025-01-01T00:00:00Z",
  "end": "2025-01-01T00:30:00Z",
  "percentiles": [0.5, 0.9, 0.99],
  "limits": { "offset": 0, "limit": 10 }
}
```

Response (trimmed):

```json
{
  "count": 10,
  "total": 10,
  "limit": 10,
  "offset": 0,
  "defaultPercentiles": [0.5, 0.9, 0.95, 0.99],
  "data": {
    "intervals": [
      {
        "from": "2025-01-01T00:00:00Z",
        "to": "2025-01-01T00:01:00Z",
        "series": [
          {
            "key": { "dimensions.channel": "web", "dimensions.region": "us-east" },
            "samples": 420,
            "sum": 165000.0,
            "mean": 392.0,
            "percentiles": { "0.5": 320.0, "0.9": 650.0, "0.99": 1200.0 }
          }
        ]
      }
    ]
  },
  "_links": {
    "self": { "href": "/api/histograms/query", "method": "POST", "body": { "..." : "..." } }
  }
}
```

The controller returns the configured default percentiles even if the request overrides them. `series[].sum` stores the running total of the measurement (e.g., total milliseconds), `samples` is the count, and `mean` is precomputed if the sketch can expose it cheaply.

> **Known limitation (Dec 2025):** Histogram queries only return series for the exact key combinations requested. If you omit a keyed dimension (for example leaving out `dimensions.channel`) the API hashes the “empty” value and fails to match any stored rows, so you get zero samples instead of an aggregate. Workaround: either include the explicit value list for each key (`["web","mobile","partner"]`, `["us-east","us-west","eu-central"]`, etc.) and perform aggregation client side, or define a companion histogram with no keyed dimensions when you need a pre-aggregated view.

## REST State Transition Query

State detection emits transition counters (A→B) and snapshots automatically. Query them via `/api/query/state-transitions`:

```http
POST /api/query/state-transitions
Content-Type: application/json

{
  "serviceKey": "payments",
  "objectType": "UserProfile",
  "attribute": "user.status",
  "fromStates": ["NEW", "ACTIVE", "SUSPENDED"],
  "toStates": ["ACTIVE", "ARCHIVED"],
  "interval": "5m",
  "start": "2025-01-01T00:00:00Z",
  "end": "2025-01-01T01:00:00Z",
  "limits": { "offset": 0, "limit": 6 }
}
```

Response:

```json
{
  "count": 6,
  "total": 12,
  "limit": 6,
  "offset": 0,
  "data": {
    "intervals": [
      {
        "start": "2025-01-01T00:00:00Z",
        "end": "2025-01-01T00:05:00Z",
        "transitions": [
          { "fromState": "NEW", "toState": "ACTIVE", "count": 42 },
          { "fromState": "SUSPENDED", "toState": "ARCHIVED", "count": 3 }
        ]
      }
    ]
  },
  "_links": {
    "next": { "href": "/api/query/state-transitions", "method": "POST", "body": { "offset": 6, "limit": 6, "...":"..." } }
  }
}
```

The service key must match the logical service configured in `service_registry`. `objectType` and `attribute` correspond to the entries in `stateExtractors`. The server automatically aligns the requested interval to an available bucket (`5s`, `1m`, `5m`, `1h`, `1d`, `7d`). For `fromStates` / `toStates`, omit the field (or pass an empty array) to include everything, add `"*"` to explicitly request all states, and use `"(none)"` (case-insensitive) to include transitions that originated the first time an object enters a state. Responses also surface that value as `"(none)"` so dashboards can render initial transitions without any internal placeholder.

### State Transition Filters Cheat Sheet

- `fromStates`/`toStates` omitted or `[]`: return every transition that matches the rest of the payload.
- Include `"*"`: explicitly request *all* states while still allowing other tokens, e.g. `["*", "(none)"]` means “everything plus first-time transitions.”
- Include `"(none)"`: query for transitions whose origin (or destination) was previously untracked. This is emitted when a state extractor sees an object attribute for the first time.
- Mix-and-match tokens: `["(none)", "ACTIVE"]` finds objects that were first seen or already ACTIVE, while `["ARCHIVED"]` focuses on a specific destination.
- Tokens are case-insensitive, so `(NONE)` or `(None)` work the same.

### State Count Time Series (new)

The ingestion pipeline now snapshots `object_state_counts` into `object_state_count_timeseries` every minute (`M1` bucket). Use `/api/query/state-count-timeseries` to plot how many objects were ACTIVE/ARCHIVED/etc. over time without replaying transitions yourself:

```http
POST /api/query/state-count-timeseries
Content-Type: application/json

{
  "serviceKey": "payments",
  "objectType": "UserProfile",
  "attribute": "user.status",
  "states": ["ACTIVE", "SUSPENDED", "ARCHIVED"],
  "interval": "1m",
  "start": "2025-11-03T10:00:00Z",
  "end": "2025-11-03T11:00:00Z",
  "limits": { "offset": 0, "limit": 30 }
}
```

Response snippet:

```json
{
  "count": 30,
  "total": 60,
  "data": {
    "intervals": [
      {
        "from": "2025-11-03T10:00:00Z",
        "to": "2025-11-03T10:01:00Z",
        "states": [
          { "state": "ACTIVE", "count": 4201 },
          { "state": "SUSPENDED", "count": 310 },
          { "state": "ARCHIVED", "count": 98 }
        ]
      }
    ]
  }
}
```

Tuning knobs:

- `obsinity.stateCounts.timeseries.enabled` (default `true`) toggles the snapshot job.
- `obsinity.stateCounts.timeseries.snapshotRateMillis` (default `60000`) controls the cadence.
- Supported intervals: `"1m"` (raw snapshot), `"5m"`, `"1h"`, `"1d"` rollups, and any other duration that is a multiple of one minute (e.g., `"30m"`, `"4h"`, `"2d"`). Non-rollup intervals reuse the `1m` snapshot taken at that minute; we simply drop the seconds component of your request and step forward by the interval.
- If you omit `start`/`end`, the query uses the earliest available snapshot (or a 7-day default window) through “now,” and both boundaries are aligned to minute precision.

## REST State Count Query

To inspect the current distribution of objects per state, use `/api/query/state-counts`:

```http
POST /api/query/state-counts
Content-Type: application/json

{
  "serviceKey": "payments",
  "objectType": "UserProfile",
  "attribute": "user.status",
  "states": ["ACTIVE", "BLOCKED", "ARCHIVED"],
  "limits": { "offset": 0, "limit": 20 }
}
```

Response:

```json
{
  "count": 3,
  "total": 6,
  "limit": 20,
  "offset": 0,
  "data": {
    "states": [
      { "state": "ACTIVE", "count": 420 },
      { "state": "BLOCKED", "count": 15 },
      { "state": "ARCHIVED", "count": 83 }
    ]
  },
  "_links": {
    "self": { "href": "/api/query/state-counts", "method": "POST", "body": { "serviceKey": "payments", "objectType": "UserProfile", "attribute": "user.status", "states": ["ACTIVE", "BLOCKED", "ARCHIVED"], "limits": { "offset": 0, "limit": 20 } } },
    "next": { "href": "/api/query/state-counts", "method": "POST", "body": { "serviceKey": "payments", "objectType": "UserProfile", "attribute": "user.status", "limits": { "offset": 20, "limit": 20 } } }
  }
}
```

If `states` is omitted, all states are returned (subject to `limit`). Counts are maintained live by the state extractor pipeline, so this endpoint reflects the latest snapshot at query time.

## Default Controllers & Service Configs

| Module | Default port | Purpose / Endpoints |
| ------ | ------------ | ------------------- |
| `obsinity-controller-rest` | 8080 (see `application.yml`) | `/events/publish`(single), `/events/publish/batch`, `/api/search/events`, `/api/catalog/*`, `/api/objql/query`, `/api/query/counters`, `/api/histograms/query`, `/api/query/state-transitions`, `/api/query/state-counts`, `/api/query/state-count-timeseries`. |
| `obsinity-controller-admin` | 8080 (inherits Spring Boot default when run standalone) | `/api/admin/config/ready`, `/api/admin/config/service` (JSON `ServiceConfig` ingest), `/api/admin/configs/import` (tar/tgz CRD archives). |
| `obsinity-ingest-rabbitmq` | n/a (worker) | Spring Boot worker that consumes canonical Obsinity payloads from `obsinity.ingest.rmq.queue` (default `obsinity.events`) and pushes them through `EventIngestService`. Enable with `obsinity.ingest.rmq.enabled=true`. |
| `obsinity-ingest-kafka` | n/a (worker) | Spring Boot worker built on Spring Kafka. Reads from `obsinity.ingest.kafka.topic` using the configured bootstrap servers/group/client IDs and hands each payload to the same ingest pipeline. Enable with `obsinity.ingest.kafka.enabled=true`. |
| `obsinity-reference-service` | 8086 (`src/main/resources/application.yml`) | Bundles the REST + Admin controllers with the storage layer, Flyway migrations, config loader, and optional RMQ/Kafka workers. Ships as the default server for local development and is the target for the JVM Collection SDK (`EventSender` defaults to `http://localhost:8086/events/publish`). |

The broker workers default to disabled; flip `obsinity.ingest.rmq.enabled=true` and/or `obsinity.ingest.kafka.enabled=true` on the reference service (or the workers themselves) to start consuming. When you run `obsinity-controller-rest` directly it uses `server.port=8080` and connects to `jdbc:postgresql://localhost:5432/obsinity` (see its `application.yml`). The reference service overrides those values to line up with `docker-compose`.

Key server config snippets (reference service):

```yaml
server:
  port: 8086
spring:
  datasource:
    url: jdbc:postgresql://obsinity-db:5432/obsinity
    username: obsinity
    password: obsinity
obsinity:
  api.hal:
    embedded: data
    links: links
  counters.persist.workers: 10
  counters.flush.max-batch-size: 5000
  counters.flush.rate.s5: 5000
  histograms.persist.workers: 10
  stateTransitions.persist.workers: 4
```

Enable automatic CRD ingestion by setting (see `application-local.yml`):

```yaml
obsinity:
  config:
    init:
      enabled: true
      location: "classpath:/service-definitions/"
      cron: "0 * * * * *"
```

## Pipeline Configuration Properties

The ingest workers share a common schema via `obsinity.service.core.config.PipelineProperties`. Every pipeline exposes the same knobs:

| Property | Default | Description |
| -------- | ------- | ----------- |
| `obsinity.counters.persist.workers` | `10` | Number of async writers draining the counter buffer into Postgres. |
| `obsinity.counters.persist.queue-capacity` | `20000` | Max queued flush jobs; backpressure kicks in when saturated. |
| `obsinity.counters.flush.max-batch-size` | `5000` | Slice each bucket flush into chunks of this size before handing to the executor. |
| `obsinity.counters.flush.rate.s5` | `5000` (ms) | Fixed-rate schedule for draining the 5-second bucket. Higher buckets roll up automatically. |
| `obsinity.histograms.persist.workers` | `10` | Same contract as counters but for histogram sketches. |
| `obsinity.histograms.persist.queue-capacity` | `20000` | Queue size for histogram flush jobs. |
| `obsinity.histograms.flush.max-batch-size` | `5000` | Max histogram batch size per flush. |
| `obsinity.histograms.flush.rate.s5` | `5000` (ms) | Flush cadence for histogram buffers. |
| `obsinity.stateTransitions.persist.workers` | `4` | Workers persisting state transition counts. |
| `obsinity.stateTransitions.persist.queue-capacity` | `5000` | Queue capacity for transition batches (defaults smaller because cardinality is lower). |
| `obsinity.stateTransitions.flush.max-batch-size` | `5000` | Flush batch size for transition rows. |
| `obsinity.stateTransitions.flush.rate.s5` | `5000` (ms) | Flush cadence for transition counters. |
| `obsinity.stateExtractors.enabled` | `true` | Toggle for running `StateDetectionService` inside `JdbcEventIngestService`. |
| `obsinity.stateExtractors.loggingEnabled` | `true` | Log every detected transition (useful for debugging). |

All properties can be set via `application.yml`, environment variables, or system properties when running the controllers.

## Operational Notes

* Each pipeline (counters, histograms, state transitions) flushes the 5-second bucket on `obsinity.*.flush.rate.s5` and cascades rollups to `1m`, `5m`, `1h`, `1d`, and `7d`.
* Hash caches for counter key materialisation are controlled with `obsinity.counters.hash.cache-size` and `obsinity.counters.hash.ttl`.
* `StateDetectionService` compares incoming attribute values against the snapshot repository. `stateExtractors` must be configured per service (`state-extractors.yaml`) otherwise transitions are ignored.
* Rollups rely on deterministic hashes, so you can scale query APIs separately from ingest; no sticky-session requirement.

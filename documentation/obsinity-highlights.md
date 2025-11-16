# Obsinity Highlights

## Why Obsinity Is In A Class Of Its Own

Obsinity unifies raw event storage, multi-dimensional metrics, state tracking, and rich query APIs in one Postgres-backed stack. Client SDKs use declarative annotations, server modules materialise counters/histograms/states at ingest, and every API responds with HAL+pagination so UI builders and dashboards can plug in immediately.

## Long-Term Raw Event Storage

- `obsinity-service-core` writes every event into partitioned Postgres tables (`events_raw` + `event_attr_index`).
- Time-based LIST→RANGE partitioning keeps week-by-week retention cheap; TTL per service/event controls cleanup without losing metric history.
- Attribute indexes are maintained separately for fast lookups even when partitions age out slowly.

## Multi-Dimensional Search

- `/api/search/events` accepts JSON filters that map to indexed attributes, boolean logic, and time ranges.
- The attribute index stores flattened key paths, enabling queries like `http.status=500 AND user.id IN ('a','b')` without scanning raw JSON.
- Results include `_embedded.events` plus HAL links for pagination.

## Multi-Dimensional Counters

- MetricCounter definitions live next to events (YAML CRDs → ServiceConfig) with `key.dimensions` describing arbitrary attribute tuples.
- Ingest path hashes every dimension combination deterministically and materialises counts in fixed rollups (5s → 7d) with per-metric granularity (`S5`, `M1`, `M5`).
- REST: `/api/counters/query` emits interval slices with HAL pagination so dashboards can render directly.

## Multi-Dimensional Histograms

- Histogram specs declare which attribute to bucket, percentiles to precompute, and optional bucket layout overrides.
- DDSketch accumulators maintain quantile accuracy across rollups, so wide ranges (minutes to weeks) stay precise.
- `/api/histograms/query` returns per-interval percentile maps (`"percentiles": {"0.5": 320.0, ...}`) with counts and sums.

## Multi-Attribute State & Transition Counters

- `stateExtractors` point at any attribute path (including dotted/nested paths). When incoming events change those attributes, `StateDetectionService` updates snapshots and transition buffers.
- Queries: `/api/query/state-transitions` returns `from → to` counts per interval, exposed via HAL so UX can paginate/scroll.

## HAL-Style Responses With Interval Support

- All metric/search endpoints emit HAL documents (`count`, `total`, `_embedded`, `_links`).
- Interval payloads (`data.intervals[]`) include `from/to` timestamps, meaning dashboards can just iterate the array and plot points.

## Variable Granularity Configurations

- Counters and histograms accept `specJson.granularity` (5s/1m/5m) to control flush cadence and default rollups.
- State transition buffers reuse the same granularity system so cross-metric alignment is trivial.

## Exceptionally Configurable

- Service configs arrive via JSON or tar/gz CRD bundles, with scriptable `derived` attributes, retention settings, and per-metric TTLs.
- Pipeline properties (`obsinity.counters.*`, `obsinity.histograms.*`, `obsinity.stateTransitions.*`) let operators tune worker counts, batch sizes, flush rates, and hash cache behaviour.

## Runs On Stock PostgreSQL

- Every storage component targets vanilla Postgres 14+ (Flyway migrations ship with each service). No custom extensions required.
- Partitioning + deterministic schema mean you can deploy anywhere Postgres runs (on-prem, managed cloud, containerised).

## Multi-Protocol Ingestion

- HTTP/HTTPS via `obsinity-controller-rest`.
- RabbitMQ via `obsinity-ingest-rabbitmq` (queue-driven consumer, optional standalone process).
- Kafka via `obsinity-ingest-kafka` (Spring Kafka listener with manual ack & dead-letter logging).
- All share the same canonical JSON mapper so producers don’t care which path the server uses.

## Extensive Java Annotation-Based Collection SDK

- `@Flow`, `@Step`, `@PushAttribute`, `@PushContextValue`, `@FlowSink`, `@OnFlowCompleted`, etc., let client teams instrument code declaratively.
- Transport plug-ins (`obsinity-client-transport-*`) cover HTTP stacks plus RabbitMQ; ServiceLoader + Spring Boot auto-config pick the best available sender.
- Testkit (`obsinity-client-testkit`) provides in-memory senders so flows can be asserted during unit tests.

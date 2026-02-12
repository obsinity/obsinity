# Server Patterns (Obsinity)

Obsinity servers treat applications as event emitters. Derived metrics are defined and materialized on the server side. The client never implements counter or histogram logic, but it must emit event data that is compatible with the configured metrics (key dimensions, value paths, object ids, and state attributes).

**Core Principles**
- Config-first behavior: services, events, indexes, metrics, and state extractors are defined in configuration and loaded into an in-memory snapshot.
- Derived metrics are materialized at ingest time (counters, histograms, state snapshots, and state transitions).
- Event indexing is configuration-driven and scoped per event type.

**Config-Driven Pipeline**
1. `ConfigInitCoordinator` loads service definitions from `obsinity.config.init.location` on startup and on a schedule.
2. `ResourceConfigSource` parses YAML/JSON service definitions and also accepts CRD-style documents (`obsinity/v1`, kinds `event`, `metriccounter`, `metrichistogram`).
3. `ConfigMaterializer` normalizes `ServiceConfig` models into runtime `EventTypeConfig`, `CounterConfig`, `HistogramConfig`, and state extractor definitions.
4. `ConfigRegistry` publishes an immutable snapshot; `ConfigLookup` is the read API used by ingest and query services.

**Ingest-to-Metrics Flow**
- `EventEnvelopeMapper` converts canonical JSON into `EventEnvelope`.
- `JdbcEventIngestService` persists the raw event and fans out to derived pipelines:
- `AttributeIndexingService` indexes configured attribute paths into `event_attr_index` and stores distinct values.
- `CounterIngestService` increments in-memory counters using configured keyed dimensions.
- `HistogramIngestService` records histogram samples using configured value paths and dimensions.
- `StateDetectionService` applies configured state extractors to update snapshots and transition counters.
- Unconfigured services or event types are routed to `UnconfiguredEventQueue`.

**Derived Metrics Families**
- Counters: configured per event type with `keyedKeys` (dimensional counters) and granularity. Missing key data skips the increment.
- Histograms: configured per event type with `value` path (or default duration) and dimensions; samples are stored in sketches.
- State metrics: configured via state extractors (object type, object id field, state attributes). Generates snapshots and transition counts (A -> B).

**Buffer -> Flush -> Persist Pattern**
- Counters: `CounterBuffer` -> `CounterFlushService` -> `CounterPersistExecutor` -> `CounterPersistService`.
- Histograms: `HistogramBuffer` -> `HistogramFlushService` -> `HistogramPersistExecutor` -> `HistogramPersistService`.
- Both pipelines use scheduled flushes and configurable batch sizes.

**Implications For Clients**
- Clients emit events; they do not compute counters or histograms.
- Clients must include the attributes needed by the configured metrics (dimensions, value paths, object ids, and state fields).

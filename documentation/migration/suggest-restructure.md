# Top‑level layout

```
obsinity/
├─ client/                         # producer (emit) only
│  ├─ obsinity-client-api
│  ├─ obsinity-client-core
│  ├─ obsinity-client-transport-spi
│  ├─ obsinity-client-transport-*
│  ├─ obsinity-collection-sink-logging
│  ├─ obsinity-client-testkit
│  └─ obsinity-client-otel-adapter      # <— new (optional)
├─ engine/                         # consumer (ingest/rollup/query)
│  ├─ obsinity-engine-app
│  ├─ obsinity-engine-api
│  ├─ obsinity-engine-storage
│  ├─ obsinity-engine-service
│  ├─ obsinity-engine-web
│  ├─ obsinity-engine-messaging
│  ├─ obsinity-engine-otel-endpoint     # <— new (optional)
│  └─ obsinity-engine-testkit
└─ shared/                         # zero-business-logic, no Spring
   ├─ obsinity-wire                 # <— native ingestion format + schema
   ├─ obsinity-types                # value objects (IDs, buckets, enums)
   └─ obsinity-otel-wire            # OTEL ↔︎ Obsinity mappers (pure, small)
```

## Dependency directions (keep it acyclic)

```
shared/*  ←  client/* (selected)
shared/*  ←  engine/*

client/*  ✗  engine/*   (no cross-deps)
```

---

# What goes in **shared**

### 1) `shared/obsinity-wire`  *(native ingestion format)*

Pure DTOs and JSON (or protobuf/avro) schema that both **client** and **engine** agree on.

**Packages:** `com.obsinity.shared.wire.*`

**Key classes (rename Chronograf equivalents accordingly):**

* `EventEnvelope` — transport wrapper (traceId, spanId, time, kind, lifecycle, origin, attributes, context, errors)
* `IngestBatch` — `List<EventEnvelope>`
* `IngestResponse` — ack + per-item error list
* `AttributeValue` — typed union (string, long, double, boolean, bytes, list, map)
* `EventKind` (SERVER, CLIENT, PRODUCER, CONSUMER, INTERNAL)
* `Lifecycle` (FLOW\_STARTED, FLOW\_FINISHED, STEP\_STARTED, STEP\_FINISHED, ERROR, …)
* `TimeBucket`/`BucketSize` (PT5S, PT1M, PT5M, PT1H, …) — if you must expose to clients

> All previously “Chronograf models” used on the wire live here now, not in engine/api.

### 2) `shared/obsinity-types`  *(reusable VOs & utilities)*

Zero‑dependency (no Spring/JPA). Small helpers used by both sides.

**Packages:** `com.obsinity.shared.types.*`

* `EventId`, `TraceId`, `SpanId` (value objects with parsing/format)
* `Origin` (service, region, version)
* `Hashing` (attribute hashing) — deterministic, stable
* `TimeRange`, `InstantRange`, `WindowParser` (basic; keep heavy parsers in engine)
* Error codes/enums (`IngestErrorCode`)

### 3) `shared/obsinity-otel-wire`  *(OTEL mapping, no engine/client logic)*

Stateless converters between OTEL types and Obsinity wire DTOs.

**Packages:** `com.obsinity.shared.otel.*`

* `OtelToObsinity` — `SpanData`/`ReadableSpan` → `EventEnvelope`/`IngestBatch`
* `ObsinityToOtel` *(optional)* — for export the other way
* Mapping constants (attribute key conventions)

> Depends only on OpenTelemetry **API** (not SDK) if possible. Keep it as light as you can.

---

# Client side additions

### `client/obsinity-client-otel-adapter` (optional)

Turn live OTEL spans into Obsinity events and send via your chosen transport.

**Packages:** `com.obsinity.client.otel.*`

* `OtelSpanExporter` (implements OTEL `SpanExporter`) → uses `OtelToObsinity` + `ObsinityClient`
* Minimal settings (`endpoint`, `apiKey`, `service.name`) via system props or Spring Boot auto-config (if you want a starter later)

> This keeps `client-core` free of OTEL, and teams can opt in.

---

# Engine side additions

### `engine/obsinity-engine-otel-endpoint` (optional)

Accept **OTLP** directly (HTTP/protobuf or JSON), convert to native, feed ingest pipeline.

**Packages:** `com.obsinity.engine.otel.*`

* `OtlpHttpController` or Netty endpoint → decode OTLP → `OtelToObsinity` → call `EventIngestService`
* Back-pressure / batch sizing
* Feature flag to enable/disable

---

# Where Chronograf files go (quick mapping)

* **Chronograf DTOs** (`EventPublishRequest`, `Rollup*`, `TimeBucket`, etc.)

    * If they are *wire-level* ingestion items → `shared/obsinity-wire`
    * If they are *engine API responses for your REST* (e.g., rollup result shapes) → `engine/obsinity-engine-api`

* **Entities & Repos** → `engine/obsinity-engine-storage`

* **Services** (ingest, counters, rollup, bucket resolvers, time-range resolvers) → `engine/obsinity-engine-service`

* **Controllers** → `engine/obsinity-engine-web`

* **Rabbit listeners/config** → `engine/obsinity-engine-messaging`

* **Exception advice** → `engine/obsinity-engine-web`

* **Hashing utils** (if needed by client too) → `shared/obsinity-types` (`Hashing`)

* **Parsing utilities**

    * Generic window/bucket parsers used on both sides → `shared/obsinity-types` (keep them minimal)
    * Heavy, engine-specific time parsing/validation → `engine/obsinity-engine-service`

---

# Package & class rename guide

* Root: `net.theresnolimits.chronograf` → `com.obsinity`
* Wire DTOs: `com.obsinity.shared.wire.*`
* Shared types: `com.obsinity.shared.types.*`
* OTEL mapping: `com.obsinity.shared.otel.*`
* Client emitters: `com.obsinity.client.*`
* Engine consumer: `com.obsinity.engine.*`

**Examples:**

* `models.ChronografRollupRequest` → `com.obsinity.engine.api.rollup.RollupRequest`
* `models.EventPublishRequest` *(if it’s your native ingest payload)* → `com.obsinity.shared.wire.IngestBatch` (or `IngestRequest`)
* `database.entities.ChronografEventCountEntity` → `com.obsinity.engine.storage.entity.EventCountEntity`
* `service.ChronografRollupService` → `com.obsinity.engine.service.RollupService`

---

# Versioning & compatibility

* Publish **shared** first and treat `obsinity-wire` as a **stable contract** (semver, changelog).
* `client-*` and `engine-*` depend on the same `obsinity-wire` version; manage via your **BOM**.
* If you evolve the wire format, add a `schemaVersion` in `EventEnvelope` and keep decode **backward compatible** on the engine.

---

# Validation strategy

* Put structural validation in `obsinity-wire` (e.g., bean validation annotations: non-null fields, size limits).
* Put semantic validation in engine service (e.g., “kind must be SERVER|CLIENT for started/finished”, “attributes size caps”).
* Shared `IngestErrorCode` used by both client (to classify retries) and engine (to respond).

---

# Minimal code shape (orientation only)

```java
// shared/obsinity-wire
package com.obsinity.shared.wire;
public final class EventEnvelope {
  public String schemaVersion;
  public String traceId, spanId, parentSpanId;
  public long timestampNanos;
  public EventKind kind;
  public Lifecycle lifecycle;
  public Origin origin;                 // service, region, version
  public Map<String, AttributeValue> attributes;
  public Map<String, AttributeValue> context; // non-indexed hints
  public ErrorInfo error;               // optional
}

// shared/obsinity-otel-wire
package com.obsinity.shared.otel;
public final class OtelToObsinity {
  public static EventEnvelope fromSpanData(SpanData span) { /* mapping */ }
}
```

---

# TL;DR

* Create **shared** with `obsinity-wire` (native ingestion) + `obsinity-types` (VOs) + `obsinity-otel-wire` (mappers).
* Keep **client** free of engine code; add an optional **client OTEL adapter**.
* Keep **engine** focused on ingest/rollup/query; add an optional **OTLP endpoint** module.
* Move Chronograf code into **engine** modules; move any wire‑level DTOs into **shared** so both sides reuse one contract.

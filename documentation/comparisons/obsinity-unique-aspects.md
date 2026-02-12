# Unique Aspects of Obsinity (Client + Server)

This document highlights what is distinct about Obsinity compared to business‑event platforms (e.g., Dynatrace and similar tools). It focuses on architectural posture and developer experience rather than feature‑by‑feature claims.

**Positioning Summary**
Obsinity treats applications as event emitters and pushes metric derivation into a server‑side, configuration‑driven pipeline. The client SDK focuses on capturing rich, structured business events with minimal coupling to downstream analytics definitions. This separation is the core differentiator.

## Server-Side Uniqueness

**Config-First Metric Derivation**
- Derived metrics (counters, histograms, state transitions) are configured centrally and materialized at ingest time.
- Service and event schemas are loaded into an in‑memory snapshot, enabling fast, lock‑free reads in the ingest pipeline.
- Configuration can be expressed as CRD‑style documents, enabling GitOps‑style workflows.

**Event-Centric Storage + Derived Pipelines**
- Raw events are persisted first, then indexing and derivation fan out from the same ingest path.
- Attribute indexing is explicitly configured per event type (not implicit or blanket‑wide).
- State extraction is first‑class: snapshots and transitions are materialized as part of ingest.

**Granularity & Rollup Awareness**
- Counters and histograms operate at configured granularities with buffered rollups.
- The server enforces query‑time constraints derived from ingest granularity, making storage cost and query fidelity explicit.

**Operational Posture**
- Config snapshot swap pattern allows refresh without locking hot paths.
- Unconfigured events can be routed into a dead‑letter queue instead of silently dropped.

## Client-Side Uniqueness

**Events, Not Metrics**
- The SDK never computes counters or histograms; it emits structured events only.
- The client is responsible for emitting attributes compatible with configured metrics (dimensions, value fields, object identifiers), but not for metric logic.

**Flow/Step Semantics**
- First‑class `@Flow` and `@Step` semantics capture business process intent and sub‑steps, not just spans.
- Orphan steps auto‑promote to flows to avoid telemetry loss while still alerting developers.

**Pluggable, Decoupled Delivery**
- Sinks and transports are separate: capture and dispatch are independent modules.
- Multiple transports (HTTP, RabbitMQ, etc.) can be swapped without changing instrumentation.

**Low-Friction Integration**
- Spring AOP auto‑config captures flows with minimal boilerplate.
- Thread‑local cleanup and validation guardrails are built in to keep overhead low and payloads safe.

## Comparison Notes (Against Business-Event Platforms)

**Obsinity distinguishes itself by:**
- Centralizing metric definition and derivation on the server (config‑driven) rather than requiring client code or dashboards to encode metric logic.
- Treating business events as the primitive, with explicit configuration describing how metrics are derived from them.
- Making dimensionality and rollup a first‑class, explicit part of the ingestion pipeline.
- Providing a dedicated state transition pipeline (snapshots + transitions) instead of relying on ad‑hoc aggregation queries.

**Tradeoffs / Intentional Choices**
- Instrumentation depends on event design: clients must emit data suitable for configured metrics.
- The separation of event emission and metric derivation means configuration discipline matters (schema, naming, attribute consistency).

## Comparison Notes (Against General-Purpose Datastores)

Obsinity is intentionally not “query-time metrics.” While Obsinity uses PostgreSQL, it treats it as storage for raw events and derived metric tables. Metric derivation happens in the ingest pipeline (code), not in SQL. By contrast, platforms built around Influx or MongoDB push derived metrics into query/aggregation layers (InfluxQL/Flux or Mongo aggregations), and similar approaches in other systems often require expert-authored pipelines to get the right rollups.

**Obsinity distinguishes itself by:**
- Deriving counters, histograms, and state transitions at ingest time instead of running aggregation pipelines at query time.
- Treating the derived metric store as a first-class output of the ingest pipeline, not as a view layered on top of raw events.
- Enforcing metric configuration (dimensions, rollups, granularity) centrally rather than embedding those rules inside queries.

**Implications:**
- Predictable query costs and latency because heavy aggregation work is moved to ingest.
- Stronger guarantees around metric definitions and rollups across teams (less ad-hoc divergence).
- Requires up-front agreement on event schemas and metric configurations.
- Historical backfill is a planned capability, not currently implemented: the design assumes derived metrics can be regenerated from stored events if the required attributes are present. Newly defined derived metrics can benefit from this once the feature lands. In query-time systems (or hard-coded pipelines), experts must author and run historical aggregation jobs to backfill past data.

## Why This Matters
- Teams can evolve metrics without redeploying application code.
- Product and analytics stakeholders can define new derived metrics centrally.
- Engineering teams focus on emitting correct, rich events, while the server handles metric semantics.

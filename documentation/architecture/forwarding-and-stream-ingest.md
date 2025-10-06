# Forwarding (Connectors) and Stream Ingestion (Kafka/RabbitMQ)

This document details how Obsinity forwards events to additional targets (connectors) and ingests events from Kafka/RabbitMQ streams into the same pipeline used by REST ingest.

## Goals
- Reuse the canonical `EventEnvelope` and REST body shape everywhere.
- Keep delivery semantics explicit and configurable (at‑least‑once vs exactly‑once with outbox/de‑dupe).
- Allow horizontal scaling and independent failure isolation for connector and consumer workers.

## 1) Connector Architecture (Forwarding)

- Abstraction
  - Define a `ForwardingConnector` SPI (server‑side), analogous to client `EventSink`, but consuming `EventEnvelope`.
  - Implementations push to external systems (Kafka, RabbitMQ, S3, remote Postgres, etc.).

- Delivery semantics
  - At‑least‑once: on transaction commit of `events_raw`, enqueue `(event_id, target)` into a work queue (outbox table or broker).
  - Exactly‑once (recommended):
    - Outbox pattern in Postgres: `event_outbox(event_id, target, created_at, attempts, last_error, delivered_at)`.
    - Ingest writes the outbox row in the same DB transaction as the event.
    - A dispatcher polls the outbox in small batches, calls the connector, and marks `delivered_at` on success (with idempotent downstream writes).

- Connector examples
  - Kafka: configurable topic naming (by service/domain), key selection (event_id or attribute), partitioner, headers (traceparent, correlation).
  - RabbitMQ: exchange/route key templates, persistent delivery, DLQ + dead‑letter exchange, retry backoff.
  - S3: time‑bucketed files (e.g., 5‑minute windows), `*.json.gz`, manifest/marker files, optional Parquet/ORC in future.
  - Remote Postgres: UPSERT into a remote table or call an RPC function; prepared statements + idempotent keys.

- Mapping
  - Flatten the REST body or serialize the canonical `EventEnvelope` as JSON.
  - Preserve: `time.startedAt` (or `time.startUnixNano`), `event`, `resource.service`, `trace`, `status`, and `attributes`.
  - If required by legacy consumers, also include `startedAt` as an alias to `time.startedAt`.
  - Optional projection: drop large fields (events/links) per target config.

- Configuration
  - Global: enable connectors, batch size, concurrency, backoff policy.
  - Per connector: target endpoint/DSN, topic/exchange/bucket templates, credentials (env/secret manager), projection rules.

## 2) Stream Ingestion (Kafka/RabbitMQ)

- Consumers
  - Stateless workers consume messages from Kafka topics or RabbitMQ queues and convert them to the REST body JSON shape.
  - Reuse the same mapping helpers the REST controller uses to produce `EventEnvelope`.
  - Backpressure via consumer group control (Kafka) or prefetch limits (RabbitMQ).

- Delivery semantics
  - At‑least‑once: commit Kafka offsets (or ack RMQ) after successful DB insert.
  - De‑duplication and DLQ:
    - Incoming messages MUST carry an `eventId`. The ingest path computes a SHA‑256 over the canonicalized payload.
    - If an event with the same `eventId` exists and the SHA‑256 matches, the message is a duplicate and is discarded.
    - If an event with the same `eventId` exists but the SHA‑256 differs (payload mismatch), the message is sent to a Dead Letter Queue (DLQ) with diagnostic metadata.
  - Exactly‑once (optional):
    - Maintain an idempotency table `(source, event_id)` checked inside the ingest transaction; still apply SHA‑256 guard for mismatch detection.

- Error handling
  - Poison messages routed to DLQ (Kafka) or dead‑letter exchange (RabbitMQ) after N retries.
  - Structured logs with message metadata (offset/partition/routing key) and root cause.

- Configuration
  - Kafka: bootstrap servers, group.id, topic patterns, poll/batch sizes, max.poll.interval.ms, security.
  - Rabbit: host/port/vhost, queue names, prefetch, confirms, heartbeat.
  - JSON mapping: whether messages are already in REST shape; if not, add transforms/adapters.

## 3) Operational Model

- Scaling
  - Connector dispatchers and stream consumers are independent processes; scale based on lag/queue length.
  - Use small batches (100–1000) with adaptive backoff to avoid DB hotspots and burst traffic.

- Observability
  - Per‑connector metrics: processed/sec, success rate, retries, DLQ counts.
  - Per‑consumer metrics: lag, processing latency, ack rates.
  - Include trace headers on outbound messages (`traceparent`, correlation ID) for cross‑system tracing.

- Security
  - No secrets in config files; use env vars or secret stores (Vault, AWS/GCP secret managers).
  - Network policies between services and brokers/buckets.

## 4) Implementation Plan (Phased)

- Phase A — Interfaces & tables
  - Add `ForwardingConnector` SPI and outbox table/migrations.
  - Dispatcher skeleton with pluggable connectors and backoff.

- Phase B — Connectors
  - KafkaConnector (producer), RabbitConnector (publisher), S3Connector (batch writer), PgConnector (remote upsert).
  - Config surfaces and docs per connector.

- Phase C — Stream consumers
  - KafkaConsumer worker and RabbitConsumer worker.
  - Shared mapping to `EventEnvelope` and idempotency option.

- Phase D — Hardening
  - Retry strategies, DLQ wiring, metrics, dashboards, structured audit logs.

---
This design lets Obsinity act both as a central telemetry store and as a hub to publish events to other systems, while allowing ingestion from existing event streams.

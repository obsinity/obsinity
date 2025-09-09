# Obsinity Deep Dive — Flushers, Counters, Histograms, Gauges

This document describes how Obsinity processes raw events, maintains in-memory counters, and coordinates flushers across multiple instances.

---

## 1) Ingestion → Raw Events

**Contract**

* Envelope is OTEL-shaped. `service.id` is mandatory.
* Timestamps must be sane (start ≤ end, both within retention window).
* Mixed event types in a batch are allowed.

**Normalization**

* For each **event type**, a registry defines:

    * Required/optional attributes (typed).
    * Which attributes are **indexed** (explicit list).
    * Dimension key order (stable canonicalization).
    * Masking rules (drop/hash before storage).

**Storage**

* `events_raw` is **typed columns + attribute projection**, not general JSONB querying:

    * Base columns: `service_id`, `event_type`, `name`, `outcome`, `started_at`, `ended_at`, `trace_id`, `span_id`, `kind`, `error_code`, `error_message` (optional), …
    * **Projected attribute columns** for explicitly indexed attributes only.
    * A **dimensions hash** (`dim_hash`) computed from indexed attributes to make joins/aggregations cheap.

**Partitioning scheme**

* Two-level strategy:

    1. **LIST partitioning by service\_id** → isolates each service’s workload, makes pruning efficient.
    2. **RANGE partitioning by started\_at (weekly)** inside each service partition.
* Example table name:

  ```
  events_raw_srv_{service_id}_2025w37
  ```
* Benefits:

    * Per-service pruning avoids cross-tenant bloat.
    * Weekly ranges keep partitions a manageable size (≤ tens of GB), easing vacuum and index maintenance.

**Indexes**

* B-tree on `(service_id, started_at)`; `(event_type, started_at)`.
* Attribute indexes on declared fields.
* **No JSONB queries**. Attributes not indexed are not query-optimized.

---

## 2) In-Memory 5s Layer (Per-Instance Aggregators)

Each ingest instance maintains **in-memory shards** for the current 5-second window, keyed by:

```
(service_id, event_type, interval_start_5s, dim_hash)
```

For each key:

* **Counter accumulator**: `count += 1`
* **Histogram accumulator**: `bucket_counts[]` incremented by bucket rules.
* **Gauge accumulator**: `(value, observed_at)` with reducer (latest/min/max).

**Late arrivals**: if an event maps to a past interval still open in memory, it updates normally. If not, see §6 (late events).

---

## 3) Rollups & Buckets

* Every 5s window is the **source of truth** for higher windows (1m, 1h, 1d, 7d).
* When a 5s window is flushed, the flusher **also updates** the parent windows in the same transaction.
* Sustains **5k–10k TPS** by batching keys and using additive upserts.

---

## 4) Flusher Design (Single-Instance Behavior)

**Trigger**

* A flush tick fires after each 5s boundary, e.g., T+5.0s → tick at T+5.2–5.6s with jitter.

**Batching**

* Collect all keys for the closed interval.
* Chunk into batches (e.g., \~1k keys) for efficient writes.

**Writes**

* **Counters**: additive upsert (`count = count + EXCLUDED.count`).
* **Histograms**: element-wise array addition.
* **Gauges**: conflict resolution by `observed_at` (latest/min/max).

---

## 5) Multi-Instance Coordination

Multiple instances flush safely **without leader election**, using:

* **Aligned windows** (everyone agrees on 5s boundaries).
* **Additive, idempotent upserts** (commutative operations).
* **Jittered flush offsets** to avoid write spikes.

**Optional advisory locks** can smooth extreme hotspots.

---

## 6) Idempotency & Late Data

**Idempotency**

* Updates are **associative and commutative** → natural idempotency.
* After a successful flush, in-memory accumulators are cleared.

**Late events**

* If the 5s shard still exists in memory, the update applies directly.
* If the shard has been cleared, the flusher **re-creates the 5s bucket**, applies the late update, and **flushes through the normal process** (same additive upserts into `*_5s` and parent tables).
* Gauges are always guarded by `observed_at` to prevent older values overwriting newer ones.

---

## 7) Table Families

* **Counters**: `event_counts_5s`, `event_counts_1m`, `event_counts_1h`, `event_counts_1d`, `event_counts_7d`
* **Histograms**: `event_histograms_*`
* **Gauges**: `event_gauges_*`

All keyed by `(service_id, event_type, interval_start, dim_hash)` and partitioned the same way (LIST by service → RANGE weekly).

---

## 8) Why This Works

* **Throughput**: in-memory batching amortizes row cost; additive upserts keep transactions small.
* **Correctness**: additive counters and histogram merges are safe under concurrency; gauges resolve by time.
* **Simplicity**: no distributed state; flushers are independent and idempotent.
* **Resilience**: late events re-create buckets and follow the same flush path; no fragile merge shortcuts.

---

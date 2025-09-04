# **Obsinity Engine: Event & Counter Processing Specification**

---

## 1. Ingestion

**Inputs**

* **Event envelope** (OTEL-like):

    * `event_id` (unique, client-supplied, idempotency key)
    * `ts` (event timestamp, not server clock)
    * `event_type` (logical schema)
    * `tenant/schema` (multi-tenancy)
      * OTEL uses resource node (resource.service)
    * **attributes** (resource attributes + span attributes + optional outcome fields)

**Process**

1. **Validation**

    * Reject if `event_id` is missing or malformed.
    * Reject if `ts` is invalid or outside allowed skew.
    * Enforce schema: only declared event types are accepted; indexed fields must exist where required.

2. **Idempotency Gate**

    * First insert of a given `event_id` → **accepted**.
    * Replay with same `event_id` → **duplicate** → complete no-op.
    * Replay with same `event_id` but different payload → **conflict** → rejected, no side effects.

3. **Routing**

    * **Raw event** → always stored in full, time-partitioned.
    * **Index materialisation** → only performed for newly accepted events.
    * **Metric derivations** (counters, histograms, gauges, states) → only performed for newly accepted events.

---

## 2. Raw Storage

* Holds the **canonical record** of every accepted event.
* Events are immutable once stored.
* All attributes are preserved in raw storage, including non-indexed ones.
* Retention is configurable; typically longer than index retention.
* Raw storage is the **source of truth** for rebuilds, backfills, and audit.

---

## 3. Indexing

* Only **declared indexed attributes** are materialised for search.
* Index tables are **supplementary**; they never replace the raw store.
* Used exclusively for **WHERE-clause pruning** in queries.
* If an event is a duplicate, no index update occurs.
* If index rebuild is needed, it is performed by replaying from raw storage.

---

## 4. Counter Buffering (5-Second Layer)

**Purpose**

* Prevent high-frequency counters from overwhelming storage.
* Collapse many increments into a single update per key per 5-second window.

**Mechanism**

* Each counter update is accumulated in memory, keyed by:

    * Tenant/schema
    * Counter name or event type
    * Dimension set (declared indexed fields and their values)
    * Bucket start time (aligned to 5 seconds)

* Each accumulator stores:

    * **Counters** → sum/count
    * **Gauges** → average/min/max/last (policy-based)
    * **Histograms** → bucket counts
    * **States** → transition tallies

**Flush Triggers**

* Expiry of 5-second window.
* Memory thresholds exceeded.
* Service shutdown or rotation.

**Flush Behavior**

* Produce exactly one record per key per 5-second bucket.
* Writes are idempotent: same `(counter, dimensions, bucket)` may be re-flushed safely.
* Duplicated events never reach the buffer, so metrics are never recalculated.

---

## 5. Materialised Rollups

**Hierarchy**

* **Always materialised**: `5s → 1m → 1h → 1d → 7d`.
* **Maximum horizon**: 7 days. Nothing above 7d is pre-aggregated.

**Process**

* Rollup workers continuously aggregate new lower-level buckets into higher ones.
* Aggregation functions:

    * Counters → sum
    * Gauges → policy (avg/min/max/last)
    * Histograms → bucket merges
    * States → rule-driven merges
* Rollups are **idempotent** and can be recomputed from lower levels if needed.

---

## 6. Interval Queries

**Query Types**

* **USING ROLLUP** → select exact materialised level (`5s`, `1m`, `1h`, `1d`, `7d`).
* **INTERVAL <duration>** → request arbitrary window size.

**Execution**

* Engine chooses the nearest available rollup ≤ requested interval.
* Results are re-bucketed into the requested interval:

    * Example: `INTERVAL 30s` → group six 5-second buckets.
    * Example: `INTERVAL 3h` → group three 1-hour buckets.
    * Example: `INTERVAL 9d` → composed from 1-day or 7-day buckets (policy).

**Policies**

* `max_materialised = 7d` (hard cap).
* Queries beyond 7d are composed from available bases, not materialised.
* Interval alignment and boundary handling are configurable:

    * Allow or forbid partial first/last buckets.
    * Pad incomplete windows with nulls or drop them.

---

## 7. Search Flow

1. **Index pruning**: apply `WHERE` on indexed attributes only.
2. **Raw filtering**: apply additional `FILTER` predicates on raw JSON of matched events.
3. **Rollup resolution**: select correct table or interval grouping based on query.
4. **Aggregation**: performed only on materialised data.
5. **Response formats**:

    * Row-oriented → one row per (time bucket × dimension tuple).
    * Bucketed/series → compact, time-aligned arrays.

---

## 8. Idempotency Rules (Hard Contracts)

* **Raw events**

    * First `event_id`: stored.
    * Replay with same `event_id`: ignored.
    * Replay with different payload: rejected.

* **Indexes**

    * Only updated on first acceptance.
    * No updates for duplicates.

* **Metrics**

    * Only derived from newly accepted events.
    * Duplicates never recalc metrics.

* **Rollups**

    * Only aggregate from 5-second layer, which already excludes duplicates.
    * Safe to rebuild at any time.

---

## 9. Operational Guarantees

* **Freshness**: 5-second counters visible within one flush cycle; higher rollups lag by at most one worker sweep.
* **Consistency**: queries always hit materialised data; no query-time recalculation of metrics.
* **Scalability**: ingestion decoupled from rollup; rollups are incremental.
* **Auditability**: raw is canonical; indexes and rollups are rebuildable.
* **Determinism**: repeated queries over the same window and config produce identical results.

---

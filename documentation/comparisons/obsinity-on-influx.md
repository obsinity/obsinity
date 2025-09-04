# **Obsinity-on-InfluxDB: Impact Analysis**

## 1. Ingestion

* **Current (Postgres)**:

    * `event_id` enforced as unique → strict idempotency.
    * Raw + index + metric rollups handled atomically in one transaction.

* **With Influx**:

    * Influx has no concept of `event_id` uniqueness → you’d need a custom dedupe layer **before ingestion**.
    * Influx line protocol doesn’t natively carry “raw JSON + selective index + counter semantics” — you’d have to map events into **measurements/tags/fields**.
    * Buffering logic (5s counters) would need to be implemented **outside Influx** (like Obsinity’s own buffer service) and only flushed deltas pushed in.

---

## 2. Indexing & Attributes

* **Postgres model**: Selective indexing (`service.name`, `region`, etc.), everything else stored raw JSON. Index rebuildable from raw.
* **Influx model**:

    * Tags (always indexed) → map to indexed attributes.
    * Fields (not indexed) → map to non-indexed attributes.
    * No “rebuild index from raw” — if you pick wrong tags vs fields up front, schema drift is hard to fix.
    * Complex nested OTEL attributes don’t map cleanly into tag/field model.

---

## 3. Counters, Histograms, Gauges, States

* **Postgres model**: Obsinity materialises 5s counters, histograms, gauges, states natively, then rolls them up to 7d.
* **Influx model**:

    * Counters can be represented as fields; rollups require **continuous queries**.
    * Histograms and state machines are **not first-class** — you’d have to encode them into multiple fields or measurements.
    * Obsinity would still need to **own** rollup and merge logic outside Influx, otherwise you lose determinism.

---

## 4. Rollups & Intervals

* **Postgres**: fixed rollup ladder (5s→7d) with arbitrary interval queries handled by re-bucketing.
* **Influx**: supports downsampling with retention policies + continuous queries, but:

    * No hard 7d horizon; retention is flexible.
    * Arbitrary intervals (`GROUP BY time(…​)`) are supported, but if you didn’t pre-compute rollups, queries may scan raw → higher latency.
    * To replicate Obsinity’s “always pre-materialised, never computed at query time” guarantee, Obsinity would need to orchestrate Influx continuous queries itself.

---

## 5. Query Model

* **Postgres**: OB-SQL compiles to SQL; rich WHERE pruning + FILTER via JSON.
* **Influx**: OB-SQL would need to compile to **InfluxQL or Flux**.

    * Mapping simple queries is fine (`SELECT count … GROUP BY time`).
    * But advanced filters, joins, or nested attributes are hard to express in Influx.
    * You’d lose some richness unless Obsinity does **hybrid query execution** (part in Influx, part in Obsinity runtime).

---

## 6. Durability & Rebuilds

* **Postgres**: full ACID, canonical raw retained, rebuildable indexes/rollups.
* **Influx**:

    * Durable, but no ACID multi-table transaction model.
    * No canonical raw JSON store; once tags/fields are chosen, structure is fixed.
    * Harder to rebuild metrics/indexes later.

---

## 7. Operational Model

* **Postgres-backed**: one general-purpose engine (Postgres) that can also handle OLTP/analytics.
* **Influx-backed**: specialized metrics store; great for dashboards, less flexible for compliance-heavy or mixed workloads.

---

# **Summary**

* **What works well if Obsinity used InfluxDB:**

    * Ingestion throughput for *pure numeric metrics* (counters, gauges).
    * Quick dashboard queries (`GROUP BY time(1m)`) for recent data.
    * Simpler retention policies (auto-expire old data).

* **What breaks or gets harder:**

    * Idempotency by `event_id` (you’d need an external dedupe gate).
    * Rich attribute indexing & nested OTEL schemas (Influx tag/field split is too rigid).
    * First-class histograms and state counters (not supported in Influx).
    * Deterministic rollups (Influx continuous queries don’t guarantee Obsinity’s semantics).
    * Rebuildability (no canonical raw JSON to replay).

---

👉 In other words: **Obsinity-on-InfluxDB** would look like “an OTEL-flavored Influx proxy with custom buffers, dedupe, and rollups bolted on.” You’d gain ingestion simplicity but lose schema flexibility, rebuildability, and strong idempotency.

---

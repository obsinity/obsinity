# 18-Month Roadmap (6 Releases, 3-month cycles)

---

## **R1 – “Foundry” (Months 0-3)**

**Theme:** Bedrock storage + indexes + minimal OB-SQL/JQL.
**Scope:**

* Raw event storage in partitioned buckets.
* Indexed attributes for WHERE.
* Event DDL: `CREATE/ALTER/DROP/DESCRIBE EVENT`.
* OB-SQL v0.1 + OB-JQL v0.1 + Criteria Builder stub.
* CLI alpha: connect + run queries.
* OTLP/HTTP + OTLP/gRPC ingest (raw events).

**Perf Target:**

* **Ingest:** ≥ 2k ev/s sustained.
* **Query:** p95 ≤ 750 ms over 1-day raw window.

---

## **R2 – “Forge” (Months 3-6)**

**Theme:** Query richness — FILTER, INTERVAL, schemas.
**Scope:**

* FILTER on non-indexed attributes.
* INTERVAL for bucketed row output.
* Row, HAL, and compressed output formats.
* Schema DDL (`CREATE/DROP SCHEMA`, grants).
* CLI beta: `--format` switch, schema browser.

**Perf Target:**

* **Ingest:** ≥ 2.5k ev/s.
* **Query:** p95 ≤ 650 ms over 7-day raw window.

---

## **R3 – “Anvil” (Months 6-9)**

**Theme:** Configurable counters + first aggregates.
**Scope:**

* Materialised counters at ingest (5s→1m→1h→1d→7d→1mo rollups).
* Aggregations: `SUM`, `RATE`, `GROUP BY`.
* Counter DDL (`CREATE/ALTER/DROP/DESCRIBE COUNTER`).
* OTEL export: counters → OTLP.
* CLI: `SHOW COUNTERS`, `EXPLAIN COUNTER`.

**Perf Target:**

* **Ingest:** ≥ 3k ev/s.
* **Counter queries:** p95 ≤ 600 ms for 7-day, ≤ 750 ms for 30-day.

---

## **R4 – “Hammer” (Months 9-12)**

**Theme:** State counters + histograms v0.9.
**Scope:**

* State counters: transitions + current state.
* Histograms: fixed buckets, precomputed p50/p90/p99.
* DDL for `STATE_COUNTER` and `HISTOGRAM`.
* Query polish: GROUP BY + interval with rollups.
* CLI: browse state counters and histograms.

**Perf Target:**

* **Ingest:** ≥ 3.5k ev/s.
* **State/hist queries:** p95 ≤ 650 ms over 30-day.

---

## **R5 – “Bellows” (Months 12-15)**

**Theme:** Gauges + histogram v1 (dynamic schemes).
**Scope:**

* Historical gauges with last/max/avg.
* Histograms v1: fixed, exponential, or log schemes.
* DDL for `GAUGE` and advanced histograms.
* Criteria Builder: full DDL validation.
* CLI wizards, dry-run diffs for metric creation.

**Perf Target:**

* **Ingest:** ≥ 4k ev/s.
* **Gauge/hist queries:** p95 ≤ 700 ms over 30-day.

---

## **R6 – “Steel” (Months 15-18)**

**Theme:** OTEL data-source completeness + HA + GA tooling.
**Scope:**

* Obsinity as full OTEL data source (counters, histograms, gauges, states).
* OTEL Collector integration recipes.
* HA/backfill with throttling.
* RBAC, quotas, audit logs.
* CLI GA: profiles, scripting, export to parquet/csv/jsonl.
* Complete developer + operator guides.

**Perf Target:**

* **Ingest:** ≥ 5k ev/s.
* **Any query:** p95 ≤ 700 ms over 30-day.
* Backfill impact ≤ 20% increase in p95.

---

# Release Code-Name Theme

Still the **blacksmith’s toolkit** progression:

* **R1 Foundry** → foundation.
* **R2 Forge** → shaping queries.
* **R3 Anvil** → counters hammered out.
* **R4 Hammer** → shaping states & histograms.
* **R5 Bellows** → gauges and refined histograms.
* **R6 Steel** → hardened, production-ready OTEL platform.

---

# 📅 Obsinity 18-Month Roadmap (2-Week Sprints, 9 per Release)

---

## **R1 – “Foundry” (Months 0-3)**

**Theme:** Raw storage, indexing, OB-SQL v0.1, CLI alpha.
**Target perf:** ≥ 2k ev/s ingest; p95 ≤ 750ms over 1-day raw.

* **Sprint 1:** Catalog & DDL skeleton (`CREATE/DROP EVENT`).
* **Sprint 2:** Raw ingest API (event\_id + ts; partitioned storage).
* **Sprint 3:** Index model v0.1 (indexed vs non-indexed attributes).
* **Sprint 4:** Idempotency tests (dedupe enforcement).
* **Sprint 5:** OB-SQL parser v0.1 (minimal grammar).
* **Sprint 6:** OB-JQL v0.1 (canonical JSON).
* **Sprint 7:** Criteria Builder stub (safe OB-SQL/JQL generation).
* **Sprint 8:** CLI alpha (connect, run queries, table output).
* **Sprint 9:** Perf & stabilization (≥2k ev/s, query p95 ≤750ms).

---

## **R2 – “Forge” (Months 3-6)**

**Theme:** Query richness — FILTER, INTERVAL, formats, schemas.
**Target perf:** ≥ 2.5k ev/s; p95 ≤ 650ms over 7-day raw.

* **Sprint 1:** FILTER support (non-indexed attributes).
* **Sprint 2:** INTERVAL engine (bucketed output).
* **Sprint 3:** HAL-style JSON renderer.
* **Sprint 4:** Compressed row format.
* **Sprint 5:** Schema DDL (`CREATE/DROP SCHEMA`).
* **Sprint 6:** Grants/RBAC basics (`GRANT/REVOKE USE`).
* **Sprint 7:** CLI beta (format switching, schema browser).
* **Sprint 8:** Perf test (≥2.5k ev/s, 7-day query ≤650ms).
* **Sprint 9:** Hardening (bugfix, docs, integration tests).

---

## **R3 – “Anvil” (Months 6-9)**

**Theme:** Counters + rollups.
**Target perf:** ≥ 3k ev/s; counter queries ≤600ms (7-day), ≤750ms (30-day).

* **Sprint 1:** Counter DDL (`CREATE/ALTER/DROP COUNTER`).
* **Sprint 2:** Counter ingest (5s rollup, cascade to 1m/1h/…).
* **Sprint 3:** SUM rollup operator.
* **Sprint 4:** RATE() function.
* **Sprint 5:** GROUP BY enforcement (dimension tuples).
* **Sprint 6:** CLI: `SHOW/EXPLAIN COUNTER`.
* **Sprint 7:** Criteria Builder support for counters.
* **Sprint 8:** OTEL export v0.1 (counters → OTLP Sum).
* **Sprint 9:** Perf/stabilization (≥3k ev/s, counters ≤600ms).

---

## **R4 – “Hammer” (Months 9-12)**

**Theme:** State counters + histograms v0.9.
**Target perf:** ≥ 3.5k ev/s; state/hist queries ≤650ms / 30-day.

* **Sprint 1:** State counter DDL.
* **Sprint 2:** Transition tracking (`ANY→X`).
* **Sprint 3:** Current state counts.
* **Sprint 4:** Histogram DDL (bucket edges).
* **Sprint 5:** Fixed bucket ingest.
* **Sprint 6:** Precomputed percentiles (p50/p90/p99).
* **Sprint 7:** Query polish (GROUP BY + rollups).
* **Sprint 8:** CLI: `SHOW STATE_COUNTERS/HISTOGRAMS`.
* **Sprint 9:** Perf/stabilization (≥3.5k ev/s, queries ≤650ms).

---

## **R5 – “Bellows” (Months 12-15)**

**Theme:** Gauges + histograms v1 (dynamic).
**Target perf:** ≥ 4k ev/s; gauge/hist queries ≤700ms / 30-day.

* **Sprint 1:** Gauge DDL (`CREATE GAUGE`).
* **Sprint 2:** Gauge ingest (last/max/avg).
* **Sprint 3:** Histogram v1 (dynamic strategies).
* **Sprint 4:** Criteria Builder: gauges + advanced hist validation.
* **Sprint 5:** CLI wizards for metric creation.
* **Sprint 6:** Dry-run plan diffs (preview apply).
* **Sprint 7:** Quota enforcement (per-tenant metric limits).
* **Sprint 8:** Perf test (≥4k ev/s, gauge queries ≤700ms).
* **Sprint 9:** Docs + reference examples.

---

## **R6 – “Steel” (Months 15-18)**

**Theme:** OTEL data-source completeness + HA + GA tooling.
**Target perf:** ≥ 5k ev/s; all queries ≤700ms / 30-day; backfill ≤20% impact.

* **Sprint 1:** OTEL export: state counters → gauge/summary.
* **Sprint 2:** OTEL export: histograms → histogram.
* **Sprint 3:** OTEL export: gauges → gauge.
* **Sprint 4:** Backfill service (resumable, throttled).
* **Sprint 5:** HA features (replication, failover).
* **Sprint 6:** RBAC + audit logs.
* **Sprint 7:** CLI GA (scripting, profiles, export).
* **Sprint 8:** Operator tooling (dashboards, lag monitoring).
* **Sprint 9:** Perf + GA stabilization (≥5k ev/s, OTEL dashboards validated).

---

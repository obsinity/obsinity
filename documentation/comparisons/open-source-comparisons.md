# 📊 Obsinity vs Other Open Source Observability Engines

| Feature / Project      | **Obsinity** (your design)                                                 | **TimescaleDB**                   | **VictoriaMetrics**                | **ClickHouse**             | **SigNoz**                     | **QuestDB**            | **Why Obsinity? (Rationale)**                                                                                      |
| ---------------------- | -------------------------------------------------------------------------- | --------------------------------- | ---------------------------------- | -------------------------- | ------------------------------ | ---------------------- | ------------------------------------------------------------------------------------------------------------------ |
| **Core Storage**       | PostgreSQL partitions + rollups (5s→7d)                                    | PostgreSQL hypertables            | Custom TSDB, columnar              | Custom columnar DB         | ClickHouse backend             | Custom C++ TSDB        | PostgreSQL-native storage ensures ecosystem compatibility (FDW, replication, tooling).                             |
| **Data Model**         | Bounded JSON schema (indexed vs non-indexed attrs, client IDs, timestamps) | Generic SQL tables                | Prometheus-style metrics only      | Wide-table, columnar       | OTEL schema → ClickHouse       | SQL tables, columnar   | Obsinity balances JSON flexibility with queryable structure, unlike metric-only or rigid SQL stores.               |
| **OTEL Compatibility** | **Native ingestion + SDKs**                                                | None (needs collector)            | Prometheus remote\_write           | Indirect via collectors    | Yes (end-user APM)             | None                   | First-class OTEL semantics baked into ingestion, not an afterthought.                                              |
| **Rollup Strategy**    | **Fixed rollups (5s, 1m, 1h, 1d, 7d)**, materialized at ingest             | Continuous aggregates             | Retention + downsampling           | Aggregations at query time | Relies on ClickHouse           | Partition pruning only | Predictable cost & latency: queries over 7 days never explode in runtime aggregation.                              |
| **Query Language**     | **OB-SQL + OB-JQL** (SQL-like, JSON-aware, rollup explicit)                | SQL (Postgres dialect)            | PromQL                             | SQL (ClickHouse dialect)   | SQL (ClickHouse)               | SQL (Postgres-like)    | Obsinity speaks a query language tailored for telemetry (counters, gauges, rollups) instead of general SQL/PromQL. |
| **Query Semantics**    | Row-oriented: `(time bucket × dimension tuple)`; compressed row format     | SQL aggregates                    | Time-series only, PromQL functions | Arbitrary OLAP             | Aggregated metrics/logs/traces | SQL aggregates         | Obsinity’s results align with how observability data is consumed (dashboards, SLOs).                               |
| **Event Types**        | **Events, Counters, Gauges, Histograms, State Sets**                       | Generic SQL tables                | Metrics only                       | Arbitrary tables           | Metrics, traces, logs          | Tables                 | Obsinity unifies multiple signal types under one event model.                                                      |
| **Performance Target** | 50k–150k events/sec sustained, backfill safeguards                         | 10k–50k inserts/sec typical       | Millions of samples/sec            | Millions of rows/sec       | Depends on ClickHouse          | >1M rows/sec           | Obsinity optimizes for *observability scale* on commodity hardware, not just raw ingestion speed.                  |
| **Retention Handling** | Fixed rollups + pruning                                                    | Partitioning & retention policies | Built-in retention                 | TTL per table/partition    | TTL on ClickHouse              | Partition drop         | Obsinity gives “observability-safe” retention (7d max rollups) that matches ops needs.                             |
| **Lifecycle & State**  | **Flows, steps, lifecycles, state transitions modeled in storage**         | None                              | None                               | None                       | None                           | None                   | Obsinity understands **flows** and **lifecycle events** as first-class signals, not just rows.                     |

---

## 🎯 Rationale for Implementing Obsinity

1. **Bridging SQL and Observability:**

    * SQL engines (Timescale, QuestDB, ClickHouse) are flexible but lack **observability primitives** (counters, histograms, lifecycles).
    * Metrics engines (VictoriaMetrics, Prometheus) are efficient but too **limited to flat timeseries**.
    * Obsinity combines both: **SQL-style flexibility + observability semantics**.

2. **Predictable Performance:**

    * Fixed **5s → 7d rollups** ensure that queries scale linearly with time horizon.
    * Avoids the “query-time blowup” problem that plagues Timescale continuous aggregates or ClickHouse ad-hoc queries.

3. **Developer Experience (DX):**

    * **Java SDK with annotations** (`@Flow`, `@Step`, `@Kind`) → one line of code = fully queryable telemetry.
    * Most alternatives require schema setup, exporter config, or hand-written collectors.

4. **OTEL Compatibility + Extensions:**

    * Fully OTEL-shaped ingestion, but with **extra fields** (client IDs, explicit service IDs, bounded schemas).
    * Out-of-the-box alignment with Grafana/Prometheus ecosystems.

5. **Domain-Driven State Modeling:**

    * Obsinity can model **consent → connection → account** lifecycles, or **job started → running → failed** state transitions.
    * This is not supported natively in Timescale, Victoria, ClickHouse, or SigNoz.

6. **Postgres-Native Foundation:**

    * Leverages PostgreSQL’s mature ecosystem (FDWs, backup/restore, replication, extensions).
    * Unlike purpose-built DBs (Victoria, ClickHouse), you don’t lose operational familiarity.

---

⚡ **In short**:
Obsinity is for teams that need **structured, OTEL-shaped observability data with predictable performance and built-in rollups**, while staying in the PostgreSQL world. None of the existing open-source systems deliver that full package.

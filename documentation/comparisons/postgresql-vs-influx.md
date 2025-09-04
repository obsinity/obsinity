# **Obsinity (PostgreSQL-backed) vs InfluxDB**

| Dimension                    | **Obsinity (PostgreSQL-backed)**                                                                                                                          | **InfluxDB**                                                                                                                               |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| **Data Model**               | Events follow **OTEL model** (traces, spans, metrics, logs) with attributes. Obsinity manages schemas, indexes, and rollups automatically.                | Measurement → tags (indexed) + fields (values). Optimized for metric points; schema is lightweight but less structured.                    |
| **Ingestion**                | OTEL-native ingestion, idempotent by `event_id`. Raw + index + metrics handled atomically. Duplicate events are ignored.                                  | Fast ingestion via line protocol, very high write throughput. No strong idempotency (duplicates require dedupe logic).                     |
| **Indexing**                 | Only declared indexed attributes are materialised for WHERE pruning; all attributes also stored raw. Indexes hidden from the user.                        | Tags are always indexed; fields are not. No concept of selective indexing beyond tag/field split.                                          |
| **Counters & Metrics**       | Counters, histograms, gauges, and state transitions are **materialised at ingest** (5s buffers, rollups 1m→1h→1d→7d). Deterministic and query-time cheap. | Supports downsampling via continuous queries, but not automatic. No native histograms/state counters — must be modelled manually.          |
| **Rollups & Intervals**      | Fixed rollups: 5s, 1m, 1h, 1d, 7d. Arbitrary `INTERVAL` queries supported by re-bucketing. Max materialisation horizon = 7d.                              | Retention policies + continuous queries for downsampling. Arbitrary groupings possible at query-time, but may require scanning raw points. |
| **Query Language**           | OB-SQL / OB-JQL (SQL-like + JSON) hides PostgreSQL; supports rich filtering, aggregation, arbitrary intervals. Always consistent with OTEL semantics.     | InfluxQL (SQL-like) and Flux (functional). Strong for time-bucketed queries, weaker for relational operations or complex filters.          |
| **Durability & Consistency** | Full ACID via PostgreSQL. Event uniqueness enforced. Rollups/indexes rebuildable from canonical raw.                                                      | WAL + TSM storage; eventually consistent in clusters. No strong dedupe without extra logic.                                                |
| **Extensibility**            | Unified with relational ecosystem (FDWs, BI tools, OTEL exporters, Grafana). Obsinity exposes OTEL-native APIs and custom query language.                 | Rich monitoring ecosystem (Telegraf, Chronograf, Kapacitor). Strong Grafana support. Limited outside metrics use-cases.                    |
| **Operational Model**        | General-purpose database managed by Obsinity: schema, partitions, rollups, and queries are invisible to users. Same cluster can serve other workloads.    | Purpose-built time-series DB. Simpler to run in narrow monitoring setups, but less flexible for mixed analytics.                           |
| **Use Cases**                | Enterprise observability platform; compliance-sensitive workloads; deterministic counters/histograms; analytics + monitoring combined.                    | Monitoring/metrics pipelines; high-throughput sensor or IoT data; lightweight dashboards.                                                  |

---

✅ **Summary**:

* **Obsinity (PostgreSQL-backed)**: OTEL-first, idempotent, deterministic rollups, schema hidden, powerful queries, enterprise durability.
* **InfluxDB**: lightweight, fast ingestion, simple model, great for dashboards, less suited to compliance or complex analytics.

---

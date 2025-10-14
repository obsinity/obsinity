# **Why Obsinity is More Than Time-Series, Observability, or Metrics**

---

## 1. More Than Just **Time-Series** 📈

Most time-series systems specialize in storing numeric samples efficiently. Obsinity goes further by treating **events as first-class citizens**:

* **Rich event structure** → Not just values, but contextual attributes, relationships, and IDs.
* **Materialized rollups** → Fixed rollup windows (5s → 1m → 1h → 1d → 7d) created at ingest, enabling fast queries without runtime rollup overhead.
* **State and transitions** → Captures lifecycle changes (e.g., consent granted/revoked, connection healthy/unhealthy), not just continuous signals.
* **Configurable retention** → Events and metrics are preserved for **policy-defined durations** (days, months, or years), ensuring compliance without uncontrolled growth.
* **Attribute masking** → Sensitive fields can be **masked or dropped** at ingest, configurable per event type, ensuring no confidential data is persisted.

---

## 2. More Than Just **Observability** 🔍

Observability is often framed narrowly as *logs, metrics, and traces*. Obsinity expands that boundary:

* **Unified ingestion model** → Fully OTEL-compatible at the edges, but enriched with Obsinity-native constructs like counters, gauges, histograms, and state sets.
* **Business + technical domains** → A single schema can hold infrastructure telemetry, API calls, and even domain events such as financial consents or data-sharing agreements.
* **Beyond runtime monitoring** → The platform is designed for **long-term analytics and compliance**, not just short-term visibility.
* **Cross-signal correlations** → A sync failure in one domain can be directly related to policy enforcement or external provider outages in another.

---

## 3. More Than Just **Metrics/Analytics** 📊

Traditional metrics platforms focus on number-crunching. Obsinity adds actionability:

* **OB-SQL + OB-JQL** → A query language designed for observability data, with SQL familiarity but native support for rollups, intervals, and compressed row formats.
* **Criteria builders & APIs** → Strongly typed, validated query builders prevent injection and ensure correctness.
* **Automation & decisioning** → Data isn’t just visualized—it can drive workflows directly, or act as a control fabric for external systems.
* **Publisher model** → Events and query results can be exported deterministically to Grafana, Prometheus, Snowflake, Databricks, or other forwarders.
* **Signal fabric** → Derived values (counts, states) remain linked to raw events, ensuring trust and traceability.

---

## 4. Obsinity in Action: Example 📌

In a financial data-sharing system, **accounts and transactions must sync regularly**.
Obsinity captures **every sync attempt** — start, finish, outcome, and error reason — and stores them in a structured, queryable way.

This provides the **sync service** with a flexible **external event store**, so it can:

* **Retry with DELTA** if the last attempt succeeded but returned no new transactions.
* **Stop until re-auth** if repeated failures indicate an authorization issue.
* **Backfill a 24-hour gap** if the last successful sync is too far in the past.

Here, Obsinity is not rolling up — it is simply **preserving and exposing events** within the configured retention window. And because sensitive attributes can be masked per event type, it provides **context without risk**, enabling downstream services to make better decisions safely.

---

## 5. What Obsinity *Is* 🌌

Obsinity is:

* A **time-series + event fabric** with configurable retention.
* A platform with **built-in attribute masking**, ensuring sensitive data never persists.
* A **long-term intelligence platform**, bridging observability and business domains.
* A **bridge between OTEL and advanced analytics**, designed for structured rollups and stateful reasoning.
* A **foundation for decision systems**, where data isn’t just viewed—it’s acted upon.

---

👉 In short:

* **Time-series** gives you *numbers over time*.
* **Observability** gives you *runtime visibility*.
* **Metrics/analytics** give you *insights*.
* **Obsinity gives you all of that, plus a configurable external event store with retention and attribute masking — enabling safe, actionable signals across technical and business domains.**

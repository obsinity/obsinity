# 📑 Obsinity Metrics Model — Concept Document

## 1. Core Idea

In **Obsinity**, a *metric* is **not a primitive counter/gauge field** that the client updates. Instead, it is a **definition on an event schema**.

* Events arrive with `eventType`, `timestamp`, and `attributes` (JSON-like key–value pairs).
* A **metric definition** specifies:

    1. **which field** to extract from the event (`path` into `attributes`),
    2. **which rollups** to compute (min, max, avg, median, sum),
    3. **which rollup intervals** to maintain (5s, 1m, 1h, 1d, 7d),
    4. **which dimensions** to group by (e.g., `service.id`, `api_name`, `http_status_code`, `symbol`).

On ingest, Obsinity automatically:

* extracts the value(s),
* applies the rollups,
* updates the appropriate **time buckets**,
* writes results into a **generic `metric_rollup` table**.

---

## 2. Metric Definition

### Schema (conceptual)

```yaml
id: uuid
name: text                # "closing_price"
eventType: text           # "trade"
path: text                # "$.close"
aggs: [avg, min, max]     # rollups to compute
rollups: [1m, 1h, 1d]     # bucket resolutions
group_by_keys: [symbol, exchange]
enabled: true
```

* **`path`** → JSON path into event attributes.
* **`aggs`** → defines the set of rollups stored.
* **`rollups`** → Obsinity materialises values at multiple resolutions (5s → 1m → 1h → 1d → 7d).
* **`group_by_keys`** → attributes used as dimensions (joined via the attribute index).

---

## 3. Storage

### Single generic rollup table

All metrics share one table — no per-metric schemas.

```sql
CREATE TABLE metric_rollup (
  bucket_start   timestamptz NOT NULL,
  resolution     text        NOT NULL,   -- '5s','1m','1h','1d','7d'
  metric_id      uuid        NOT NULL,   -- FK to metric definition
  dims_hash      bigint      NOT NULL,   -- hash of dimension tuple
  dims           jsonb       NOT NULL,   -- {"symbol":"AAPL","exchange":"NASDAQ"}
  rollups     jsonb       NOT NULL,   -- {"avg":189.23,"min":185.43,"max":190.32,"sum":390}
  PRIMARY KEY (bucket_start, resolution, metric_id, dims_hash)
);
```

* **rollups JSONB** holds metric results (named per definition).
* Examples:

    * `{"avgClosingValue": 126.1, "min": 123.4, "max": 129.9, "count": 500}`

---

## 4. Ingest Pipeline

For each ingested event:

1. **Lookup definitions** for this `eventType`.
2. For each definition:

    * Evaluate `predicate` (if defined).
    * Extract value via `path`.
    * Build `dims` JSON + `dims_hash`.
    * For each `rollup`:

        * Compute `bucket_start`.
        * **Upsert** into `metric_rollup`:

            * If rollup already exists, **update** it (`count += 1`, `min=LEAST()`, `avg = sum/count`).
            * If not, **insert** with initial values.
3. Cascade jobs periodically roll lower intervals into higher (e.g., 1m → 1h → 1d).

**Idempotency:** upserts are keyed by `(bucket_start,resolution,metric_id,dims_hash)`. Late data just recomputes the bucket row.

---

## 5. Query Model

### Range-only rollups

Compute over a span, no explicit bucket.

```sql
SELECT
  dim('symbol')        AS symbol,
  agg('avg')::numeric  AS avg_close
FROM METRIC 'closing_price'
WHERE time_range(
        start => TIMESTAMP '2025-07-01T00:00:00Z',
        end   => TIMESTAMP '2025-07-31T23:59:59Z'
      )
  AND dim('symbol') = 'AAPL';
```

→ Returns **one row per dimension combo**: overall average closing price in July.

---

### Interval-based rollups

Slice into buckets if an interval is specified.

```sql
SELECT
  bucket('1d', tz:'America/New_York') AS day,
  dim('symbol')                       AS symbol,
  agg('avg')::numeric                 AS avg_close
FROM METRIC 'closing_price'
WHERE time_range(
        start => TIMESTAMP '2025-07-01T00:00:00Z',
        end   => TIMESTAMP '2025-07-31T23:59:59Z'
      )
  AND dim('symbol') = 'AAPL'
ORDER BY day;
```

→ Returns **daily averages** for AAPL in July.

---

### Example: API request counts

```sql
SELECT
  bucket('1m', tz:'Africa/Dakar')     AS interval_start,
  dim('api_name')                     AS api_name,
  dim('http_status_code')             AS http_status,
  agg('count')::bigint                AS requests
FROM METRIC 'api_request_count'
WHERE time_range(
        start => TIMESTAMP '2025-04-04T00:00:00Z',
        end   => TIMESTAMP '2025-07-20T23:59:59Z'
      )
  AND dim('api_name') IN ('create_project','create_transaction','get_account_balance')
  AND dim('http_status_code') IN ('200','400','403','500')
ORDER BY interval_start ASC
OFFSET 0 LIMIT 100;
```

---

## 6. Special Metric Types

* **Counters** → derived from event counts via definitions.
* **Histograms** → store `histogram_bins` or `tdigest` inside `rollups`.
* **Gauges** → numeric snapshot values at ingest.
* **States** → `stateCounts` JSON tracks categorical state distribution per bucket.
* **State Transitions** → `transitionCounts` JSON tracks how many transitions occurred (A→B).

All of these use the same `metric_rollup` table and JSONB `rollups` payload.

---

## 7. Advantages

* ✅ **Single stable schema** → no per-metric tables.
* ✅ **Declarative metrics** → just define once, engine handles ingest + rollup.
* ✅ **Ingest-time materialisation** → no heavy query-time scans.
* ✅ **Dimension support** → group by arbitrary attributes via `dims`.
* ✅ **First-class support** for counters, gauges, histograms, states, transitions.
* ✅ **SQL-like query language (OB-SQL/JQL)** → concise, domain-friendly, with hidden CTE rewrites.
* ✅ **Auto-rollups** → efficient queries across time windows.

---

# 🔑 Example: Stock Closing Price

**Definition:**

```yaml
name: closing_price
eventType: trade
path: $.close
aggs: [avg, min, max]
rollups: [1m, 1h, 1d]
group_by_keys: [symbol, exchange]
```

**Query:**
Daily average closing price for AAPL in July:

```sql
SELECT bucket('1d', tz:'America/New_York') AS day,
       dim('symbol')                       AS symbol,
       agg('avg')::numeric                 AS avg_close
FROM METRIC 'closing_price'
WHERE time_range(
        start => TIMESTAMP '2025-07-01T00:00:00Z',
        end   => TIMESTAMP '2025-07-31T23:59:59Z'
      )
  AND dim('symbol') = 'AAPL'
ORDER BY day;
```

**Result (sample):**

| day        | symbol | avg\_close |
| ---------- | ------ | ---------- |
| 2025-07-01 | AAPL   | 189.23     |
| 2025-07-02 | AAPL   | 187.54     |
| 2025-07-03 | AAPL   | 188.10     |

---

✅ In short:
**Obsinity Metrics = declarative definitions + ingest-time rollups → queryable with SQL-like simplicity.**
Everything (counts, histograms, gauges, states, transitions) fits into one consistent pipeline and storage model.

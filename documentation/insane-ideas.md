# Obsinity Query Language (**OBSQL**) — Developer & Implementer Guide

> **Audience:** product engineers & SREs (how to query) + engine implementers (how it executes)
> **Dialect stance:** SQL‑like, purpose‑built for **pre‑calculated** telemetry rollups (no raw scans unless explicitly allowed).
> **Wire stance:** Clients send **OBSQL text**; the engine returns **JSON**. This guide shows **OBSQL queries** and **JSON response shapes** only.

---

## 1) Goals & Principles

* **Pre‑calculated first.** Queries read **pre‑aggregated time buckets** (5s/1m/5m/30m/1h/…); results are produced by **merging** those buckets.
  *Counts, averages, percentiles, histograms, TopK, gauges, state stocks & transitions are all mergeable.*

* **Index pushdown.** Attribute filters run on **SQL indexes** maintained per event type (no table scans for search).

* **Bounded cardinality.** Grouping dimensions must be **materialized** (or bounded by `IN (…)`, `TOPK`, or an explicit **brute‑force** opt‑in).

* **Clear time semantics.** `TIME` = event’s **occurredAt**; `RECEIVED_TIME` = **receivedAt**. Buckets align to a **timezone**, storage remains UTC.

* **Predictable JSON.** Every response is wrapped in a HAL‑style envelope with `_links`, `page`, optional `meta`, and a **`data`** node containing either **`intervals`** or **`rows`**.

---

## 2) Response Envelope (JSON)

All responses follow this pattern:

```json
{
  "_links": {
    "self": { "href": "/query?..." },
    "next": { "href": "/query?...&offset=10", "templated": false },
    "prev": { "href": "/query?...&offset=0",  "templated": false }
  },
  "page": {
    "offset": 0,
    "limit": 10,
    "returned": 10,
    "total": 5185
  },
  "meta": {
    "mode": "search | aggregate",
    "format": "intervals | rows",
    "rollup": {
      "requested_bucket": "30m",
      "source_level": "30m",              // or "5m" if merged up
      "timezone": "America/New_York",
      "cover": "minimal"
    },
    "approx": { "percentiles": true, "topk": true },
    "slow_path": false,                    // true if brute force was used
    "warnings": []
  },
  "data": {
    "intervals": [ /* when FORMAT INTERVALS */ ],
    "columns":   [ /* when FORMAT ROWS */ ],
    "rows":      [ /* when FORMAT ROWS */ ]
  }
}
```

### INTERVALS payload (charts / your existing API)

```json
{
  "data": {
    "intervals": [
      {
        "from": "2025-04-03T20:00-04:00[America/New_York]",
        "to":   "2025-04-03T20:30-04:00[America/New_York]",
        "counts": [
          { "key": { "api_name": "create_project", "http_status_code": "200" }, "count": 0 },
          { "key": { "api_name": "create_project", "http_status_code": "400" }, "count": 0 }
          /* … dense cartesian set; zero-filled if FILL(zero) … */
        ]
      }
    ]
  }
}
```

### ROWS payload (flat tables / BI)

```json
{
  "data": {
    "columns": [
      { "name": "ts", "type": "timestamp", "timezoneApplied": true },
      { "name": "api_name", "type": "string" },
      { "name": "http_status_code", "type": "string" },
      { "name": "requests", "type": "long" }
    ],
    "rows": [
      ["2025-04-03T20:00:00-04:00", "create_project", "200", 0],
      ["2025-04-03T20:00:00-04:00", "create_project", "400", 0]
    ]
  }
}
```

---

## 3) OBSQL Basics

### 3.1 FROM & WHERE

* Canonical form uses a single logical table:

  ```sql
  FROM events
  WHERE event = 'api_request'
  ```
* Sugar (equivalent):

  ```sql
  FROM api_request
  ```

### 3.2 Time filters

* `TIME SINCE 24h`
* `TIME BETWEEN TIMESTAMP '2025-04-01T00:00:00Z' AND TIMESTAMP '2025-04-02T00:00:00Z'`
* `RECEIVED_TIME SINCE 15m`

### 3.3 Grouping by time

* `BUCKET(30m [, TZ => 'America/New_York'])` in `SELECT` and `GROUP BY`
  Planner picks the **minimal cover** rollup ≤ requested bucket.

### 3.4 Materialized dimensions

* Use `dim("api_name")`, `dim("http_status_code")`, `dim("service")`, etc.
  *If a dimension/combination isn’t materialized, the query is rejected unless you permit brute force.*

### 3.5 Fill & format

* `FILL(zero | last | linear | none)` → regularize series after merge.
* `FORMAT INTERVALS` → interval objects; `FORMAT ROWS` → flat table.

### 3.6 Cardinality controls

* `TOPK(dim("route"), BY COUNT(), K=10)`
* `STRICT DIMENSIONS` (default) / `ALLOW BRUTE FORCE [MAX_SCAN='2h']`

---

## 4) Search (Event Listing)

> **Fast**: uses SQL indexes on event type + selected attributes; returns **events**, not rollups.

### OBSQL

```sql
SELECT id, event, occurredAt, receivedAt, data
FROM events
WHERE event = 'ConsentStatusChange'
  AND TIME BETWEEN now()-7d AND now()
  AND ( data.consentId = '0198…' OR data.status = 'ACTIVE' )
ORDER BY occurredAt ASC
LIMIT 50
```

### JSON (HAL‑wrapped, flat rows of event docs)

```json
{
  "_links": { "self": { "href": "/query?…" } },
  "page": { "offset": 0, "limit": 50, "returned": 16, "total": 16 },
  "meta": { "mode": "search", "format": "rows" },
  "data": {
    "columns": [
      { "name": "id", "type": "uuid" },
      { "name": "event", "type": "string" },
      { "name": "occurredAt", "type": "timestamp" },
      { "name": "receivedAt", "type": "timestamp" },
      { "name": "data", "type": "json" }
    ],
    "rows": [
      ["e3c0fc5a-…", "ConsentStatusChange", "2025-08-06T14:45:00Z", "2025-08-06T20:26:52.952064Z",
       { "status": "ACTIVE", "consentId": "f44aa5e5-…" }]
      /* … */
    ]
  }
}
```

---

## 5) Aggregations — Counts (Interval & Flat)

### 5.1 Interval‑grouped counts (dense)

**OBSQL**

```sql
SELECT
  BUCKET(30m, TZ => 'America/New_York') AS ts,
  dim("api_name") AS api_name,
  dim("http_status_code") AS http_status_code,
  COUNT() AS requests
FROM api_request
WHERE dim("api_name") IN ('create_project','create_transaction','get_account_balance')
  AND dim("http_status_code") IN ('200','400','403','500')
  AND TIME BETWEEN TIMESTAMP '2025-04-04T00:00:00Z' AND TIMESTAMP '2025-07-20T23:59:59Z'
GROUP BY BUCKET(30m, TZ => 'America/New_York'), api_name, http_status_code
FILL(zero)
FORMAT INTERVALS
LIMIT INTERVALS 10 OFFSET 0
```

**JSON**

```json
{
  "_links": { "self": { "href": "/query?…" }, "next": { "href": "/query?…&offset=10" } },
  "page": { "offset": 0, "limit": 10, "returned": 10, "total": 5185 },
  "meta": {
    "mode": "aggregate", "format": "intervals",
    "rollup": { "requested_bucket": "30m", "source_level": "30m", "timezone": "America/New_York", "cover": "minimal" }
  },
  "data": {
    "intervals": [
      {
        "from": "2025-04-03T20:00-04:00[America/New_York]",
        "to":   "2025-04-03T20:30-04:00[America/New_York]",
        "counts": [
          { "key": { "api_name": "create_project", "http_status_code": "200" }, "count": 0 }
          /* … cartesian set, zero-filled … */
        ]
      }
    ]
  }
}
```

### 5.2 Flat (ROWS) counts

**OBSQL**

```sql
SELECT
  BUCKET(30m, TZ => 'America/New_York') AS ts,
  dim("api_name") AS api_name,
  dim("http_status_code") AS http_status_code,
  COUNT() AS requests
FROM api_request
WHERE dim("api_name") IN ('create_project','create_transaction','get_account_balance')
  AND dim("http_status_code") IN ('200','400','403','500')
  AND TIME BETWEEN TIMESTAMP '2025-04-04T00:00:00Z' AND TIMESTAMP '2025-07-20T23:59:59Z'
GROUP BY BUCKET(30m, TZ => 'America/New_York'), api_name, http_status_code
FILL(zero)
FORMAT ROWS
ORDER BY ts, api_name, http_status_code
LIMIT 500 OFFSET 0
```

**JSON**

```json
{
  "_links": { "self": { "href": "/query?…" } },
  "page": { "offset": 0, "limit": 500, "returned": 12, "total": 207360 },
  "meta": { "mode": "aggregate", "format": "rows" },
  "data": {
    "columns": [
      { "name": "ts", "type": "timestamp", "timezoneApplied": true },
      { "name": "api_name", "type": "string" },
      { "name": "http_status_code", "type": "string" },
      { "name": "requests", "type": "long" }
    ],
    "rows": [
      ["2025-04-03T20:00:00-04:00", "create_project", "200", 0]
      /* … */
    ]
  }
}
```

---

## 6) Percentiles (t‑digest) & Histograms

> **Mergeable only**. Percentiles require **t‑digest/HDR sketches** in rollups; histograms require **pre‑binned** counts.

### 6.1 Percentiles by route (ROWS)

**OBSQL**

```sql
SELECT
  BUCKET(5m) AS ts,
  dim("http.route") AS route,
  COUNT() AS requests,
  P50("duration_ms") AS p50,
  P95("duration_ms") AS p95,
  P99("duration_ms") AS p99
FROM api_request
WHERE TIME SINCE 24h
GROUP BY BUCKET(5m), route
FORMAT ROWS
```

**JSON**

```json
{
  "meta": { "mode": "aggregate", "format": "rows", "approx": { "percentiles": true } },
  "data": {
    "columns": [
      { "name": "ts", "type": "timestamp" },
      { "name": "route", "type": "string" },
      { "name": "requests", "type": "long" },
      { "name": "p50", "type": "double", "unit": "ms", "approx": true },
      { "name": "p95", "type": "double", "unit": "ms", "approx": true },
      { "name": "p99", "type": "double", "unit": "ms", "approx": true }
    ],
    "rows": [
      ["2025-08-21T17:45:00Z", "/orders/{id}", 3241, 120.4, 184.7, 241.2]
    ]
  }
}
```

### 6.2 Merged histogram (ROWS)

**OBSQL**

```sql
SELECT
  BUCKET(1m) AS ts,
  dim("http.route") AS route,
  HIST("duration_ms", SCALE=LOG2, BOUNDS=[1,120000]) AS hist
FROM api_request
WHERE TIME SINCE 1h
GROUP BY BUCKET(1m), route
FORMAT ROWS
```

**JSON (hist cell example)**

```json
{
  "data": {
    "columns": [
      { "name": "ts", "type": "timestamp" },
      { "name": "route", "type": "string" },
      { "name": "hist", "type": "histogram" }
    ],
    "rows": [
      ["2025-08-21T17:45:00Z", "/orders/{id}", {
        "scheme": "LOG2",
        "unit": "ms",
        "bounds": [1, 120000],
        "offset": 0,
        "counts": [12, 33, 55, 41, 7, 1]
      }]
    ]
  }
}
```

---

## 7) Gauges (last/min/max/avg, delta & rate)

### 7.1 CPU utilisation per service

**OBSQL**

```sql
SELECT
  BUCKET(1m) AS ts,
  dim("service") AS service,
  G_LAST("cpu.util") AS cpu_last,
  G_MIN("cpu.util")  AS cpu_min,
  G_MAX("cpu.util")  AS cpu_max,
  G_AVG("cpu.util")  AS cpu_avg
FROM host.metric
WHERE TIME SINCE 6h
GROUP BY BUCKET(1m), service
FILL(last)
FORMAT ROWS
```

**JSON**

```json
{
  "data": {
    "columns": [
      { "name": "ts", "type": "timestamp" },
      { "name": "service", "type": "string" },
      { "name": "cpu_last", "type": "double", "unit": "%" },
      { "name": "cpu_min",  "type": "double", "unit": "%" },
      { "name": "cpu_max",  "type": "double", "unit": "%" },
      { "name": "cpu_avg",  "type": "double", "unit": "%" }
    ],
    "rows": [
      ["2025-08-21T17:48:00Z", "billing", 71.2, 65.7, 74.9, 69.8]
    ]
  }
}
```

### 7.2 Queue depth delta/rate

**OBSQL**

```sql
SELECT
  BUCKET(30s) AS ts,
  dim("queue") AS queue,
  G_LAST("queue.depth") AS depth,
  G_DELTA("queue.depth") AS delta,
  G_RATE("queue.depth", 30s) AS per_sec
FROM queue.metric
WHERE TIME SINCE 30m
GROUP BY BUCKET(30s), queue
FILL(last)
FORMAT ROWS
```

---

## 8) Multi‑Value Counters (MVC)

### 8.1 Map result (merged counts per key)

**OBSQL**

```sql
SELECT
  BUCKET(1m) AS ts,
  MVC_COUNT("features.enabled") AS counts
FROM feature.metric
WHERE TIME SINCE 2h
GROUP BY BUCKET(1m)
FORMAT ROWS
```

**JSON**

```json
{
  "data": {
    "columns": [
      { "name": "ts", "type": "timestamp" },
      { "name": "counts", "type": "map<string,long>" }
    ],
    "rows": [
      ["2025-08-21T17:50:00Z", { "betaSearch": 124, "newUI": 87, "promoBanner": 13 }]
    ]
  }
}
```

### 8.2 Top‑K (bounded cardinality)

**OBSQL**

```sql
SELECT
  BUCKET(5m) AS ts,
  dim("service") AS service,
  MVC_TOPK("request.tags", 10) AS top_tags
FROM api.metric
WHERE TIME SINCE 24h
GROUP BY BUCKET(5m), service
FORMAT ROWS
```

**JSON (cell)**

```json
{ "k": 10, "items": [ { "key": "mobile", "count": 4321 } ], "approx": true }
```

### 8.3 Use MVC key as a dimension (bounded)

**OBSQL**

```sql
SELECT
  BUCKET(30m) AS ts,
  MVC_DIM("scopes") AS scope,
  COUNT() AS uses
FROM auth.metric
WHERE MVC_DIM("scopes") IN ('read','write','admin')
  AND TIME BETWEEN TIMESTAMP '2025-08-01T00:00:00Z' AND TIMESTAMP '2025-08-02T00:00:00Z'
GROUP BY BUCKET(30m), scope
FILL(zero)
FORMAT INTERVALS
```

---

## 9) State Transitions, Deltas & Stocks

> State field with **finite states** (e.g., `consent.status`). Engine stores:
> (a) **edge counts** `(from→to)`, (b) **state deltas** (`-1` from, `+1` to), and optionally (c) **checkpointed stocks**.

### 9.1 Stock per state over time (INTERVALS)

**OBSQL**

```sql
SELECT
  BUCKET(30m, TZ=>'America/New_York') AS ts,
  STATE_STOCK("consent.status", 'ACTIVE')   AS active,
  STATE_STOCK("consent.status", 'INACTIVE') AS inactive,
  STATE_STOCK("consent.status", 'REVOKED')  AS revoked
FROM ConsentStatusChange
WHERE TIME BETWEEN TIMESTAMP '2025-04-04T00:00:00Z' AND TIMESTAMP '2025-07-20T23:59:59Z'
GROUP BY BUCKET(30m, TZ=>'America/New_York')
FILL(last)
FORMAT INTERVALS
LIMIT INTERVALS 10 OFFSET 0
```

**JSON (interval item)**

```json
{
  "from": "2025-04-03T20:00-04:00[America/New_York]",
  "to":   "2025-04-03T20:30-04:00[America/New_York]",
  "counts": [
    { "key": { "state": "ACTIVE" },   "count": 1287 },
    { "key": { "state": "INACTIVE" }, "count":  312 },
    { "key": { "state": "REVOKED" },  "count":   77 }
  ]
}
```

### 9.2 Edge count (PENDING→ACTIVE) per day (ROWS)

**OBSQL**

```sql
SELECT
  BUCKET(1d) AS day,
  TRANSITION_COUNT("consent.status", FROM 'PENDING', TO 'ACTIVE') AS activated
FROM ConsentStatusChange
WHERE TIME SINCE 30d
GROUP BY BUCKET(1d)
FORMAT ROWS
```

### 9.3 Transition matrix (ROWS)

**OBSQL**

```sql
SELECT
  BUCKET(1d) AS day,
  TRANSITION_MATRIX("consent.status", LIMIT_EDGES 50) AS matrix
FROM ConsentStatusChange
WHERE TIME BETWEEN TIMESTAMP '2025-07-01T00:00:00Z' AND TIMESTAMP '2025-07-31T23:59:59Z'
GROUP BY BUCKET(1d)
FORMAT ROWS
```

**JSON (cell)**

```json
{
  "from": {
    "PENDING":  { "ACTIVE": 921, "INACTIVE": 77, "REVOKED": 11 },
    "ACTIVE":   { "INACTIVE": 63, "REVOKED": 22 },
    "INACTIVE": { "ACTIVE": 45 }
  },
  "approx": false,
  "truncated": false
}
```

### 9.4 Net flow (deltas) per 5m bucket (ROWS)

**OBSQL**

```sql
SELECT
  BUCKET(5m) AS ts,
  STATE_DELTA("consent.status", 'ACTIVE')   AS d_active,
  STATE_DELTA("consent.status", 'INACTIVE') AS d_inactive,
  STATE_DELTA("consent.status", 'REVOKED')  AS d_revoked
FROM ConsentStatusChange
WHERE TIME SINCE 6h
GROUP BY BUCKET(5m)
FORMAT ROWS
```

---

## 10) TopK & Time‑Decayed Ranking (TDR)

* **TOPK**: heavy‑hitters for a dimension per bucket.
  Stored as mergeable sketches or pruned after merge.

* **TDR** (time‑decayed ranking): emphasize recent counts via exponential decay.
  *Optional extension*: `TOPK(dim("route"), BY COUNT(), K=10, DECAY='exp', LAMBDA=0.2)`

**OBSQL**

```sql
SELECT
  BUCKET(1m) AS ts,
  TOPK(dim("http.route"), BY COUNT(), K=10) AS route,
  COUNT() AS requests
FROM api_request
WHERE TIME SINCE 2h
GROUP BY BUCKET(1m), route
FORMAT ROWS
```

---

## 11) Brute Force (opt‑in slow path)

* Default: `STRICT DIMENSIONS` — reject unmaterialized combos.
* Opt‑in: `ALLOW BRUTE FORCE [MAX_SCAN='2h']` — permit slow evaluation from finer rollups or recent‑event indexes.
* Response marks: `meta.slow_path = true`, and may include `meta.warnings`.

**OBSQL**

```sql
SELECT BUCKET(5m) AS ts, dim("user_id"), dim("device_model"), COUNT()
FROM api_request
WHERE TIME SINCE 2h
GROUP BY BUCKET(5m), user_id, device_model
ALLOW BRUTE FORCE MAX_SCAN='2h'
FORMAT ROWS
```

---

## 12) Implementer’s Guide

### 12.1 Rollup tables (per resolution)

* **Counts & numeric fields**

  ```
  events_rollup_30m(
    ts_bucket timestamptz,
    event text,
    -- materialized dims...
    api_name text,
    http_status_code text,
    -- aggregations
    count bigint,
    sum_duration_ms double precision,
    min_duration_ms double precision,
    max_duration_ms double precision,
    avg_duration_ms_sum double precision,
    avg_duration_ms_count bigint,
    tdigest_duration_ms bytea,        -- if enabled
    hist_duration_ms_log2 int[]       -- if enabled
    -- indexes: (ts_bucket, event, api_name, http_status_code)
  )
  ```

* **Gauges (per field)**

  ```
  g_last_cpu_util double precision,
  g_min_cpu_util  double precision,
  g_max_cpu_util  double precision,
  g_sum_cpu_util  double precision,
  g_cnt_cpu_util  bigint
  ```

* **MVC**

    * Sparse map column (e.g., `jsonb` or `hstore`) or materialized dims (`mvc_key`).
    * Optional `topk_request_tags bytea` sketch.

* **States**

    * `trans_status_from_to` counters (or a compact `(from,to)->count` map)
    * `state_delta_status_<STATE> bigint`
    * Optional `state_stock_ckpt_status_<STATE> bigint` on coarse levels

### 12.2 Planner (minimal cover)

1. **Validate**: aggregates are mergeable; dims are materialized; functions available at/under requested bucket.

2. **Pick level** `L` s.t. `L ≤ requested_bucket` and the window is covered by fewest chunks.

3. **SQL pushdown**: filter by `event`, `ts_bucket`, and concrete dims; read sparse rows.

4. **Merge**: sum counts & sums; merge sketches; compute `{avg_sum, avg_count}`; add deltas; union TopK; etc.

5. **Densify / FILL**: build the requested cartesian (time × dims) and left‑join; apply fill mode.

6. **Post‑ops**: `RATE`, `RATIO`, `HAVING`, `ORDER`, pagination.

### 12.3 Timezone alignment

* Bucket edges computed in query TZ; storage remains UTC.
* For INTERVALS, render `"2025-04-03T20:00-04:00[America/New_York]"`.

### 12.4 Catalog (capabilities for planning)

Per event type record:

* Available **rollup levels**.
* **Materialized dimensions** & permitted combos.
* Enabled **sketches** (t‑digest/hist/topk) per field.
* **Gauge** fields and which gauge rollups exist.
* **State** fields (finite set), rollup levels for `trans_*`, `delta_*`, checkpoint cadence.

### 12.5 Error codes

* `E_DIM_NOT_MATERIALIZED`: “Dimension 'device\_model' not materialized for 'api\_request' at levels {5m,30m}.”
* `E_ROLLUP_UNAVAILABLE`: “No rollup ≤ 30m available for requested window.”
* `E_FUNC_UNSUPPORTED`: “P95(duration\_ms) requires t‑digest rollups; enable sketches.”
* `E_CARDINALITY_LIMIT`: “Group cardinality exceeds limit 50k; use IN(…) or TOPK.”
* `E_SLOW_PATH_DISABLED`: “Query needs brute force; add ALLOW BRUTE FORCE to proceed.”

---

## 13) Appendix

### 13.1 Grammar sketch (informal)

```
query        ::= SELECT select_list FROM (events | ident)
                 [WHERE predicate]
                 [GROUP BY group_list]
                 [HAVING predicate]
                 [FILL '(' fill_mode ')']
                 [USING ROLLUP level]
                 [FORMAT ('ROWS' | 'INTERVALS')]
                 [ORDER BY order_list]
                 [LIMIT n [OFFSET m] | LIMIT INTERVALS n [OFFSET m]]
                 [STRICT DIMENSIONS | ALLOW BRUTE FORCE [MAX_SCAN='dur']]

select_item  ::= expr [AS ident]
expr         ::= ident | number | string
               | "BUCKET(" duration ["," "TZ" "=>" string] ")"
               | "dim(" string ")"
               | agg_func "(" [args] ")"
agg_func     ::= COUNT | SUM | MIN | MAX | AVG
               | P50 | P90 | P95 | P99 | PCT
               | HIST
               | G_LAST | G_MIN | G_MAX | G_AVG | G_DELTA | G_RATE
               | MVC_COUNT | MVC_TOPK | MVC_DIM
               | TRANSITION_COUNT | TRANSITION_MATRIX | STATE_DELTA | STATE_STOCK

predicate    ::= simple_pred { (AND|OR) simple_pred }*
simple_pred  ::= ident op value
               | "dim(" string ")" op value
               | "TIME" time_op time_value
               | "RECEIVED_TIME" time_op time_value
               | "ident IN (" value { "," value }* ")"
               | "(" predicate ")"
op           ::= "=" | "!=" | "<" | ">" | "<=" | ">=" | "IN" | "BETWEEN" | "~" | "~*"
fill_mode    ::= "zero" | "last" | "linear" | "none"
```

### 13.2 Function reference (selected)

* **Time:** `BUCKET(dur[, TZ => 'Area/City'])`
* **Aggregates:** `COUNT()`, `SUM(f)`, `MIN(f)`, `MAX(f)`, `AVG(f)`
* **Percentiles:** `P50(f)`, `P90(f)`, `P95(f)`, `P99(f)`, `PCT(f, p)` *(approx)*
* **Histogram:** `HIST(f[, SCALE=LOG2|LINEAR, BOUNDS=[a,b]])`
* **Gauges:** `G_LAST(f)`, `G_MIN(f)`, `G_MAX(f)`, `G_AVG(f)`, `G_DELTA(f)`, `G_RATE(f, dur)`
* **MVC:** `MVC_COUNT(f)`, `MVC_TOPK(f, k)`, `MVC_DIM(f)`
* **States:** `TRANSITION_COUNT(f, FROM s|ANY, TO s|ANY)`, `TRANSITION_MATRIX(f[, LIMIT_EDGES k])`, `STATE_DELTA(f, s)`, `STATE_STOCK(f, s[, BASELINE 'auto'|'none'|'ts'])`
* **Series math:** `RATE(agg, dur)`, `RATIO(num, den)`
* **Cardinality:** `TOPK(dim, BY agg, K=k[, DECAY='exp', LAMBDA=x])`

### 13.3 Response field notes

* **`_links`**: HAL‑style; `self`, `next`, `prev`.
* **`page`**: `offset`, `limit`, `returned`, `total`.
* **`meta.rollup`**: chosen levels; `approx` flags.
* **`data.intervals[]`**: each has `from`, `to`, and `counts[]` with `{ "key": {<dims>}, <metrics>… }`.
* **`data.columns` / `data.rows`**: flat tables for `FORMAT ROWS`.

---

### 13.4 Practical tips

* Prefer **INTERVALS** for chart APIs; **ROWS** for CSV/BI.
* Always constrain high‑cardinality dims via `IN (…)` or `TOPK`.
* Switch to `ALLOW BRUTE FORCE` only for short windows and with `MAX_SCAN` set.
* To debug planning decisions, enable a “plan explain” mode returning `meta.plan`.

---

# **Obsinity Query Language (OB-SQL)**

**Developer + Implementer Guide**
*(with OB-JQL + OB-Q Java API)*

---

## 1. Overview

OB-SQL is the query language for the **Obsinity Engine**, designed to query **pre-aggregated observability data** (events, counters, gauges, histograms, and state transitions).

### Key design goals

* **Pre-calculated buckets**: OB-SQL queries work against pre-computed rollups (`5s, 1m, 1h, 1d, 7d`).
* **Efficient filtering**: Only **indexed attributes** may appear in the `WHERE` clause. Additional filtering goes in `FILTER`.
* **Tenant isolation**: Each tenant is a **schema**; queries must `USE schema`.
* **Dual query modes**:

  * **Event search** (`SELECT … FROM event`) → returns raw event records.
  * **Aggregation** (`SELECT … FROM counter/gauge/histogram`) → returns bucketed values.
* **Fixed rollups**: Queries may request any predefined rollup and the engine will aggregate upwards.
* **Security model**: Users/apps authenticate via **SSL certs, API keys, or passwords**. Roles control schema access.

---

## 2. Schemas, Roles & Authentication

* **Schema = Tenant** → `USE schema_name;`
* **Roles** → grant schema access:

```sql
GRANT USAGE ON SCHEMA finance TO role_analyst;
```

* **Users** → belong to roles.

  * Humans: username+password or API keys.
  * Applications: SSL certificate (mTLS).

**Example**

```sql
CREATE ROLE analyst;
CREATE USER alice WITH PASSWORD 's3cr3t' IN ROLE analyst;
CREATE USER app_service WITH CERTIFICATE 'CN=app-service' IN ROLE analyst;
```

---

## 3. Query Types (OB-SQL + OB-JQL)

### 3.1 Event Search

**OB-SQL**

```sql
USE finance;

SELECT event_id, timestamp, user_id, status
FROM ConsentStatusChange
WHERE consent_id = 'abc123' AND status = 'ACTIVE'
FILTER attributes->'region' = 'EU'
OPTIONS (minMatch = 1, sortOrder = 'asc', limit = 50, daysBack = 7);
```

**OB-JQL**

```json
{
  "use": "finance",
  "select": ["event_id","timestamp","user_id","status"],
  "from": "ConsentStatusChange",
  "where": {
    "and": [
      { "field": "consent_id", "op": "eq", "value": "'abc123'", "indexed": true },
      { "field": "status", "op": "eq", "value": "'ACTIVE'", "indexed": true }
    ]
  },
  "filter": {
    "and": [
      { "path": ["attributes","region"], "op": "eq", "value": "'EU'" }
    ]
  },
  "options": { "minMatch": 1, "sortOrder": "asc", "limit": 50, "daysBack": 7 }
}
```

---

### 3.2 Aggregation Queries

#### Counters

**OB-SQL**

```sql
USE finance;

SELECT count(*)
FROM api_request
WHERE api_name IN ('create_project','create_transaction','get_account_balance')
  AND http_status_code IN ('200','400','403','500')
USING ROLLUP 30m
BETWEEN '2025-04-04T00:00:00Z' AND '2025-07-20T23:59:59Z'
TIMEZONE 'America/New_York'
OPTIONS (offset = 0, limit = 100);
```

**OB-JQL**

```json
{
  "use": "finance",
  "select": [{ "agg": "count", "target": "*" }],
  "from": "api_request",
  "where": {
    "and": [
      { "field": "api_name", "op": "in", "value": ["'create_project'","'create_transaction'","'get_account_balance'"], "indexed": true },
      { "field": "http_status_code", "op": "in", "value": ["'200'","'400'","'403'","'500'"], "indexed": true }
    ]
  },
  "rollup": "30m",
  "between": { "from": "2025-04-04T00:00:00Z", "to": "2025-07-20T23:59:59Z" },
  "timezone": "America/New_York",
  "options": { "offset": 0, "limit": 100 }
}
```

---

#### Gauges

**OB-SQL**

```sql
USE finance;

SELECT gauge(balance)
FROM account_balance
WHERE account_id = 'xyz'
USING ROLLUP 1h
BETWEEN '2025-07-01T00:00:00Z' AND '2025-07-05T00:00:00Z';
```

**OB-JQL**

```json
{
  "use": "finance",
  "select": [{ "agg": "gauge", "target": "balance" }],
  "from": "account_balance",
  "where": { "and": [ { "field": "account_id", "op": "eq", "value": "'xyz'", "indexed": true } ] },
  "rollup": "1h",
  "between": { "from": "2025-07-01T00:00:00Z", "to": "2025-07-05T00:00:00Z" }
}
```

---

#### Histograms

**OB-SQL**

```sql
USE finance;

SELECT histogram(duration_ms)
FROM api_latency
WHERE api_name = 'get_account_balance'
INTERVAL 1h
BETWEEN '2025-07-01T00:00:00Z' AND '2025-07-02T00:00:00Z';
```

**OB-JQL**

```json
{
  "use": "finance",
  "select": [{ "agg": "histogram", "target": "duration_ms" }],
  "from": "api_latency",
  "where": { "and": [ { "field": "api_name", "op": "eq", "value": "'get_account_balance'", "indexed": true } ] },
  "interval": "1h",
  "between": { "from": "2025-07-01T00:00:00Z", "to": "2025-07-02T00:00:00Z" }
}
```

---

#### State Transitions

**OB-SQL**

```sql
USE finance;

SELECT state_transition(consent_status)
FROM ConsentStatusChange
WHERE consent_id = 'abc123'
USING ROLLUP 1d
BETWEEN '2025-07-01T00:00:00Z' AND '2025-07-05T00:00:00Z';
```

**OB-JQL**

```json
{
  "use": "finance",
  "select": [{ "agg": "state_transition", "target": "consent_status" }],
  "from": "ConsentStatusChange",
  "where": { "and": [ { "field": "consent_id", "op": "eq", "value": "'abc123'", "indexed": true } ] },
  "rollup": "1d",
  "between": { "from": "2025-07-01T00:00:00Z", "to": "2025-07-05T00:00:00Z" }
}
```

---

## 4. Interval vs Rollup

* **ROLLUP** → fixed buckets (`5s, 1m, 1h, 1d, 7d`).
* **INTERVAL** → arbitrary duration (aggregations only). Exact if aligned to rollups, otherwise approximate.

---

## 5. Response Shapes

* Always wrapped in `data`.
* HAL style `_links` for pagination.
* Tabular rows for UI.

---

## 6. Security & Access

* Tenancy by schema.
* Roles grant schema usage.
* Auth via password, API key, or mTLS.
* Full audit logging.

---

## 7. Grammar (Simplified)

```ebnf
select_stmt  ::= "SELECT" select_list
                 "FROM" source
                 where_clause?
                 filter_clause?
                 rollup_clause?
                 interval_clause?
                 between_clause?
                 timezone_clause?
                 options_clause?
```

---

## 8. OB-JQL (Canonical JSON)

### Top-level keys

* `use` — schema
* `select` — fields and/or aggregates
* `from` — source
* `where` — indexed-only predicates
* `filter` — additional conditions
* `rollup` / `interval` — one or the other for aggregations
* `between` — time bounds
* `timezone` — IANA zone
* `options` — pagination, minMatch, etc.

### Boolean & Should

```json
{ "and": [ ... ] }
{ "or":  [ ... ] }
{ "not": { ... } }
{ "should": { "minMatch": 2, "conditions": [ ... ] } }
```

---

## 9. OB-Q (Java Criteria Builder)

The **Java DSL** ensures:

* **Strings auto-quoted & escaped**.
* **Numbers unquoted**.
* **WHERE predicates always `indexed=true`**.
* Builder prevents invalid combos (e.g., interval on raw event queries).

**Key Helpers**

* `eq`, `ne`, `gt/gte/lt/lte`, `in`, `nin`, `prefix`, `like`.
* Boolean: `and`, `or`, `not`, `should(minMatch, ...)`.
* Aggregates: `count()`, `gauge(field)`, `histogram(field, params)`, `stateTransition(field)`.

---

## 10. Examples with OR vs minMatch

### Explicit OR

**Java**

```java
OBQ q = OBQBuilder.use("finance")
  .select("event_id").select("timestamp")
  .from("api_request")
  .where(OBQBuilder.or(
      OBQBuilder.eq("http_status_code", "500"),
      OBQBuilder.eq("http_status_code", "503")
  ))
  .filter(OBQBuilder.filterEq(List.of("attributes","region"), "EU"))
  .options(new OBQ.Options(null, "desc", 100, 0, 7))
  .build();
```

**OB-SQL**

```sql
USE finance;

SELECT event_id, timestamp
FROM api_request
WHERE (http_status_code = '500' OR http_status_code = '503')
FILTER attributes->'region' = 'EU'
OPTIONS (sortOrder = 'desc', limit = 100, offset = 0, daysBack = 7);
```

---

### SHOULD + minMatch

**Java**

```java
OBQ q = OBQBuilder.use("finance")
  .select("event_id").select("timestamp")
  .from("api_request")
  .where(OBQBuilder.and(
      OBQBuilder.eq("api_name", "get_account_balance"),
      OBQBuilder.should(2,
          OBQBuilder.eq("http_status_code","200"),
          OBQBuilder.eq("region_code","EU"),
          OBQBuilder.prefix("user_id","svc-")
      )
  ))
  .options(new OBQ.Options(2, "asc", 50, 0, 7))
  .build();
```

**OB-SQL**

```sql
USE finance;

SELECT event_id, timestamp
FROM api_request
WHERE api_name = 'get_account_balance'
  AND (http_status_code = '200' OR region_code = 'EU' OR user_id LIKE 'svc-%')
OPTIONS (minMatch = 2, sortOrder = 'asc', limit = 50, offset = 0, daysBack = 7);
```

---

## 11. Implementer Notes

* **Rollups** pre-created: `5s, 1m, 1h, 1d, 7d`.
* **Planner** enforces indexed fields in WHERE.
* **FILTER** executes post-retrieval.
* **minMatch** applies across a set of should-conditions, not the same as OR.
* **Emitters**:

  * OB-SQL emitter just assembles tokens.
  * Builder enforces quoting, escaping, validation.

---

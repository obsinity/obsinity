# OBSQL Developer & Implementer Guide

> **Obsinity Query Language (OBSQL)**
> A SQL-like language for querying, aggregating, and managing observability data in Obsinity Engine.
> Supports events, indexes, counters, gauges, histograms, and state transitions.
> Designed for pre-calculated rollups, fast indexed search, and schema-based multi-tenancy.

---

## 1. Introduction

Obsinity Engine stores telemetry and event data in **pre-calculated rollup tables** and indexed attribute stores.
Queries must respect these storage rules to avoid expensive brute-force scans.

Key design goals:

* **No per-query recomputation**: queries aggregate *pre-calculated rollups* (counts, percentiles, histograms).
* **Schema isolation**: each tenant = a schema, with its own events, indexes, and rollups.
* **Index-aware search**: only indexed attributes may appear in `WHERE` clauses.
* **Additional filtering**: arbitrary filters are supported in `FILTER`.
* **Fixed rollup sets**: predefined `5s`, `1m`, `1h`, `1d`, `7d`. Queries must align with these.

---

## 2. Security Model

* **Schemas**: tenants are represented as schemas. All queries run inside one schema.
* **Users**: may be human users or applications.
* **Roles**: group of privileges; users belong to roles.
* **Authentication**:

  * Human users → password or key-based.
  * Applications → API key or mutual TLS with SSL certificates.
* **Authorization**:

  * `GRANT` and `REVOKE` allow schema access to roles.
  * Example:

```sql
CREATE ROLE analyst;
GRANT USAGE ON SCHEMA finance TO analyst;
GRANT SELECT ON EVENT api_request TO analyst;

CREATE USER alice PASSWORD 'secret';
GRANT ROLE analyst TO alice;
```

---

## 3. Query Model

OBSQL queries use a subset of SQL with Obsinity extensions.

### 3.1 Core Query

```sql
SELECT field1, field2, ...
FROM event_name
WHERE indexed_field = 'value' AND ...
FILTER nonindexed_field LIKE '%foo%'
INTERVAL 1h
ROLLUP 1d
START '2025-01-01T00:00:00Z'
END   '2025-01-02T00:00:00Z'
LIMIT 100 OFFSET 0;
```

* **`SELECT … FROM event_name`**
  Always starts with an event. No joins.
* **`WHERE`**
  Only indexed attributes.
* **`FILTER`**
  Non-indexed, slower filtering.
* **`INTERVAL`**
  Groups results into buckets of arbitrary length. Does not require a predefined rollup.
* **`ROLLUP`**
  Requests pre-calculated buckets from storage. Must match one of the fixed sets (`5s`, `1m`, `1h`, `1d`, `7d`).
  Faster than `INTERVAL` because it avoids recomputation.
* **`START` / `END`**
  Time bounds.
* **`LIMIT` / `OFFSET`**
  Pagination.

---

## 4. Query Types

### 4.1 Event Search

```sql
SELECT consentId, status, timestamp
FROM ConsentStatusChange
WHERE consentId = 'abc123'
  AND status = 'ACTIVE'
START now() - 7d
END now()
ORDER ASC
LIMIT 50;
```

**Response JSON**:

```json
{
  "data": [
    {
      "eventType": "ConsentStatusChange",
      "consentId": "abc123",
      "status": "ACTIVE",
      "timestamp": "2025-07-19T12:00:00Z"
    }
  ],
  "count": 1,
  "offset": 0,
  "limit": 50,
  "_links": {
    "self": "/schemas/finance/events/ConsentStatusChange?offset=0&limit=50",
    "next": null
  }
}
```

---

### 4.2 Aggregation with INTERVAL

```sql
SELECT api_name, http_status_code, COUNT(*)
FROM api_request
WHERE api_name IN ('create_project', 'create_transaction', 'get_account_balance')
  AND http_status_code IN ('200','400','403','500')
INTERVAL 30m
START '2025-04-04T00:00:00Z'
END   '2025-07-20T23:59:59Z'
LIMIT 100 OFFSET 0;
```

**Response JSON** (intervals view):

```json
{
  "data": {
    "intervals": [
      {
        "from": "2025-04-03T20:00-04:00[America/New_York]",
        "to":   "2025-04-03T20:30-04:00[America/New_York]",
        "counts": [
          { "key": { "api_name": "get_account_balance", "http_status_code": "200" }, "count": 42 },
          { "key": { "api_name": "create_project", "http_status_code": "500" }, "count": 3 }
        ]
      }
    ],
    "offset": 0,
    "limit": 100,
    "total": 5185
  },
  "_links": {
    "self": "...",
    "next": "..."
  }
}
```

---

### 4.3 Aggregation with ROLLUP

```sql
SELECT api_name, http_status_code, COUNT(*)
FROM api_request
WHERE api_name IN ('create_project','create_transaction')
USING ROLLUP 1h
START '2025-07-01T00:00:00Z'
END   '2025-07-07T00:00:00Z';
```

* **Planner rule**: chooses 1h rollup table directly, no recompute.
* Faster than `INTERVAL 1h`.

---

### 4.4 Gauges

Gauges represent the *latest value* at query time.

```sql
SELECT temperature
FROM sensor_reading
WHERE device_id = 'dev123'
GAUGE;
```

**Response**:

```json
{
  "data": {
    "device_id": "dev123",
    "temperature": 21.7,
    "timestamp": "2025-08-22T18:00:00Z"
  }
}
```

---

### 4.5 Multi-Value Counters

```sql
SELECT http_status_code, COUNT(*)
FROM api_request
WHERE api_name = 'create_transaction'
INTERVAL 5m
START now() - 1h
END now();
```

Response returns counts for each status code.

---

### 4.6 State Transition Counters

Tracks transitions between known states.

```sql
SELECT state, TRANSITIONS(from_state, to_state)
FROM ConsentStatusChange
WHERE consentId = 'abc123'
START now() - 30d
END now();
```

Response shows decrements for `from`, increments for `to`.

---

## 5. Administrative Commands

* **Schema & Event Management**

```sql
SHOW SCHEMAS;
USE SCHEMA finance;
SHOW EVENTS;
DESCRIBE EVENT api_request;
```

* **Index Management**

```sql
CREATE INDEX ON api_request (api_name, http_status_code);
```

* **Counter / Histogram Management**

```sql
CREATE COUNTER api_request_counter ON api_request (api_name, http_status_code);
CREATE HISTOGRAM latency_histogram ON api_request (latency_ms);
```

* **Security**

```sql
CREATE USER app1 CERTIFICATE 'cert-data';
CREATE USER bob PASSWORD 'secret';
GRANT ROLE analyst TO bob;
```

---

## 6. Implementation Notes

* **Rollups**:

  * Predefined: `5s`, `1m`, `1h`, `1d`, `7d`.
  * Planner chooses smallest available rollup ≥ requested.
  * `INTERVAL` may stitch rollups.
* **Index Constraints**:

  * All `WHERE` fields must be indexed.
  * `FILTER` supports slow scans if necessary.
* **Brute Force Mode**:

  * Allowed only if explicitly requested via `ALLOW BRUTE FORCE`.
  * Warns user in response metadata.
* **Counters / Gauges / Transitions**:

  * Counters: increment only.
  * Gauges: store latest observed value.
  * State transitions: decrement `from`, increment `to`.

---

## 7. Appendix

### 7.1 Grammar Sketch

```ebnf
query ::= select_stmt
select_stmt ::= "SELECT" field_list "FROM" event_name where_clause? filter_clause?
                interval_clause? rollup_clause? start_clause? end_clause? limit_clause? offset_clause?;

where_clause ::= "WHERE" condition ( "AND" condition )*;
filter_clause ::= "FILTER" condition ( "AND" condition )*;
interval_clause ::= "INTERVAL" duration;
rollup_clause ::= "USING ROLLUP" duration_list;
start_clause ::= "START" timestamp;
end_clause ::= "END" timestamp;
limit_clause ::= "LIMIT" int;
offset_clause ::= "OFFSET" int;

duration ::= "5s" | "1m" | "1h" | "1d" | "7d";
```

### 7.2 Functions

* `COUNT(*)`
* `TRANSITIONS(from,to)`
* `PERCENTILE(field, p)` (only on precomputed histograms)
* `AVG(field)` (only if stored in rollup)

---

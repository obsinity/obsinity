# Generic Persistent Dimension Counters

## Overview
Persistent Dimension Counters (PDCs) provide durable, all-time totals per dimensioned key that survive time-series retention deletions. PDCs are updated only through event projections and use strict idempotency (eventId + counter key) to guarantee each logical event applies at most once.

## Why These Counters Are Different
Standard time-series counters are derived from retained events and represent **windowed** analytics; once events expire, historical totals can no longer be recomputed. PDCs are **materialized forever** per counter key, so they remain authoritative beyond retention limits. This difference changes expectations:

- **Source of truth**: time-series counters are recomputable from events; PDCs are authoritative state.
- **Retention impact**: time-series counters lose historical totals after deletion; PDCs do not.
- **Idempotency boundary**: events are globally unique by `eventId`; duplicates are logged, stored in an audit table, and dropped at ingestion, so PDCs rely on upstream dedupe rather than per-counter dedupe.
- **Rebuildability**: time-series counters can be rebuilt from retained data; PDCs cannot be fully rebuilt once retention has dropped events unless archival/rollups exist.

## Problem Statement
Time-series retention (e.g., 2 years) makes it impossible to compute true all-time totals after older events are deleted. PDCs persist aggregated counts forever while the time-series stream remains available for windowed analytics.

## Definitions
- **Event**: immutable record with `eventId` (client-provided), `type`, `occurredAt`, `dimensions`, `payload`.
- **Dimension Set**: key-value map attached to an event.
- **Dimension Key**: deterministic canonical representation + hash of the dimension set used for indexing.
- **Counter Key**: `(counterName, dimensionKey)`.
- **Persistent Dimension Counter (PDC)**: permanent materialized counter value per Counter Key.

## Counter Semantics
- PDC value is a signed 64-bit integer.
- Only two operations exist:
  - **INCREMENT** (+1)
  - **DECREMENT** (-1)
- Updates are applied via event projections only.
- Exactly-once is enforced **upstream** at ingestion:
  - `eventId` is globally unique.
  - Duplicate events are logged, stored in an audit table, and dropped before projection.
- Optional safety rule (per counter): **floor at 0** (never negative).

## Architecture

### High-Level Components
1) **Event Store** (retention-limited time series)
2) **Persistent Counter Store** (retained forever)
3) **Projection Processor** (consumes events, applies INC/DEC)
4) **Event Registry** (global `eventId` uniqueness)
5) **Duplicate Event Audit Store** (records duplicate `eventId`s)
6) **Optional Per-Entity State Store** (only for transition-safe mode)

### Component Diagram (Mermaid)
~~~mermaid
flowchart LR
  ES[Event Store<br/>(retention-limited)]
  PP[Projection Processor]
  ER[Event Registry<br/>(global eventId)]
  DS[Duplicate Event Audit Store<br/>(duplicate eventId)]
  CS[Persistent Counter Store<br/>(retained forever)]
  SS[Optional State Store<br/>(transition-safe)]

  ES -->|consume events| PP
  PP -->|register eventId| ER
  PP -->|audit duplicate events (ingest)| DS
  PP -->|update counter| CS
  PP -->|optional state lookup/update| SS
~~~

### Sequence Diagram (Mermaid)
~~~mermaid
sequenceDiagram
  actor Producer
  participant ES as Event Store
  participant PP as Projection Processor
  participant ER as Event Registry
  participant DS as Duplicate Event Audit Store
  participant CS as Counter Store

  Producer->>ES: append event
  ES->>PP: deliver event
  PP->>ER: insert eventId
  alt eventId is new
    PP->>CS: apply INC/DEC
    CS-->>PP: ok
  else duplicate eventId
    PP->>DS: record duplicate
    PP-->>ES: drop event
  end
~~~

## Data Model

### Counter Table (Persistent)
| Field | Type | Notes |
| --- | --- | --- |
| counter_name | text | name of the counter |
| dimension_key | text/bytea | canonical hash of dimensions |
| dimensions_json | json/jsonb | optional; for inspection |
| value | bigint | current count |
| updated_at | timestamptz | last update time |
| floor_at_zero | boolean | optional; or stored in config |

**Unique constraint:** `(counter_name, dimension_key)`

### Event Registry (Global EventId Uniqueness)
| Field | Type | Notes |
| --- | --- | --- |
| event_id | uuid/text | client-provided id |
| first_seen_at | timestamptz | when first observed |

**Unique constraint:** `(event_id)`

**Retention policy:** default retain forever for correctness. If a TTL is used, correctness is compromised because old eventIds can reapply and inflate totals.

### Duplicate Event Audit Table
| Field | Type | Notes |
| --- | --- | --- |
| event_id | uuid/text | client-provided id |
| first_seen_at | timestamptz | when first observed |
| duplicate_seen_at | timestamptz | when duplicate observed |
| event_type | text | optional for audit |
| dimensions_json | json/jsonb | optional for audit |
| payload_json | json/jsonb | optional for audit |

**Unique constraint (recommended):** `(event_id, duplicate_seen_at)` or a separate table keyed by `event_id` with a duplicates array, depending on audit needs.

## Atomicity and Concurrency
- Updates must be atomic under concurrent writers for the same Counter Key.
- Prefer DB-level atomic upserts over optimistic locking retry loops for hot counters.
- Event uniqueness check and counter update must be in **one transaction**.

### Postgres Example (Single Transaction)
~~~sql
-- 1) Attempt to register the eventId (unique)
INSERT INTO event_registry (event_id, first_seen_at)
VALUES (:event_id, now())
ON CONFLICT DO NOTHING;

-- 2) Only if row was inserted, apply update
-- (Use application check on affected rows)
INSERT INTO counters (counter_name, dimension_key, dimensions_json, value, updated_at)
VALUES (:counter_name, :dimension_key, :dimensions_json, :delta, now())
ON CONFLICT (counter_name, dimension_key)
DO UPDATE SET
  value = counters.value + EXCLUDED.value,
  updated_at = now();
~~~

**Contention guidance:**
- Hot keys will contend; rely on DB atomic updates and keep transactions short.
- If conflict retries occur, record metrics and consider sharding by dimension or spreading producers.

## Ordering and Out-of-Order Events
Ordering generally does not matter for pure totals. For gauges (active counts), ordering and duplicates can cause drift unless events represent true transitions.

### Modes
1) **Transition-safe mode (recommended)**
   - Events represent actual state transitions (connect/disconnect).
   - Optional state store ensures only valid transitions emit INC/DEC.
2) **Raw mode**
   - Apply +/-1 as-is.
   - Drift possible with noisy/duplicate/out-of-order events.

### Failure Modes
| Scenario | Raw Mode Outcome | Transition-Safe Outcome |
| --- | --- | --- |
| Duplicate eventId | dropped at ingestion | dropped at ingestion |
| Out-of-order disconnect before connect | may go negative (or floor at 0) | no decrement unless state is connected |
| Replayed old events after registry TTL | double-count | double-count (if TTL applied) |

## Configuration Model (YAML/CRD Style)

### Fields
| Field | Type | Description |
| --- | --- | --- |
| counterName | string | name of the counter |
| mode | string | `persistent` |
| dimensions | list | dimension keys to extract |
| floorAtZero | boolean | clamp at 0 if true |
| rules | list | event type to action mapping |
| idempotency.requireEventId | boolean | must be true |
| idempotency.scope | string | `globalEventId` |

### Example
~~~yaml
counters:
  - counterName: active_connections
    mode: persistent
    dimensions: [masterAccountId]
    floorAtZero: true
    rules:
      - on: account.connected
        op: increment
      - on: account.disconnected
        op: decrement
    idempotency:
      requireEventId: true
      scope: globalEventId

  - counterName: connects_total
    mode: persistent
    dimensions: [masterAccountId]
    floorAtZero: false
    rules:
      - on: account.connected
        op: increment
    idempotency:
      requireEventId: true
      scope: globalEventId
~~~

## Example Flows

### Single Connect Event
- Event: `account.connected` with `eventId=E1`.
- `active_connections` increments by +1.
- `connects_total` increments by +1.

### Disconnect Event
- Event: `account.disconnected` with `eventId=E2`.
- `active_connections` decrements by -1.

### Duplicate Delivery
- Event `E1` delivered again.
- Ingestion detects duplicate `eventId`, logs it, stores it in the duplicate audit table, and drops it.
- Projection does not apply any update; no double-count.

### Concurrent Connects
- Events `E3` and `E4` for same master arrive concurrently.
- Both dedupe inserts succeed for their eventIds.
- Counter updates apply twice; final value reflects both.

### Out-of-Order Disconnect Before Connect
- Event `account.disconnected` arrives before `account.connected`.
- **Raw mode:** decrement applies; value may go negative unless `floorAtZero` is true.
- **Transition-safe mode:** disconnect ignored if state is not connected; no negative drift. (Probably a bad idea)

## Retention and Rebuild
- Persistent counters are authoritative beyond the event retention boundary.
- Full rebuild from deleted events is impossible without archival/rollups.
- Operational recommendation:
  - Periodic export/checkpoint of counter tables for disaster recovery.
  - Optional immutable audit log of counter updates (not required for core design).

## Observability and Safety
**Metrics**
- `counter_update_success_total`
- `duplicate_event_dropped_total`
- `counter_update_conflict_retries`
- `projection_lag_seconds`

**Logging fields**
- `counterName`, `dimensionKey`, `eventId`, `op` (INC/DEC), `result` (APPLIED/DROPPED_DUPLICATE)

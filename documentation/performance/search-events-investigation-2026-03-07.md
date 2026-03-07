# Search Performance Investigation (March 7, 2026)

## Scope
Investigated slow `/api/search/events` queries for:

```json
{
  "service": "payments",
  "event": "user_profile.updated",
  "period": { "previous": "-30m" },
  "order": [{ "field": "started_at", "dir": "desc" }],
  "limit": 20
}
```

## Initial Symptoms
- Search latency reported around `1.27s+`.
- `EXPLAIN ANALYZE` showed:
  - Full-table/partition append scans in earlier plans.
  - Expensive final fetch path scanning a very large `events_raw_default`.
  - For some runs, severe join mis-planning (`Rows Removed by Join Filter` in the tens of millions).

## Investigation Changes

### 1. Added EXPLAIN logging for searches
- File: `obsinity-service-core/src/main/java/com/obsinity/service/core/search/JdbcSearchService.java`
- Added runtime explain logging with:
  - SQL
  - params
  - full plan text
  - explain runtime
- Config flag: `obsinity.search.explain.enabled`

### 2. Reworked SQL generation for search query shape
- File: `obsinity-service-core/src/main/java/com/obsinity/service/core/objql/OBJqlCteBuilder.java`
- Changes:
  - Skip `matched/matched_base` self-join path when no attribute `match` predicates are present.
  - Re-apply service/event/time predicates in final event fetch to improve pruning/index selection.

### 3. Added Flyway indexes
- Migration: `obsinity-service-core/src/main/resources/db/migration/V6__search_events_indexes.sql`
- Indexes added:
  - `ix_events_raw_default_search_service_event_started_event_id`
    - `(service_partition_key, event_type, started_at DESC, event_id)`
  - `ix_events_raw_default_search_service_event_id`
    - `(service_partition_key, event_id)`

## ANALYZE Command Runbook
After creating indexes, run statistics refresh:

```sql
ANALYZE VERBOSE obsinity.events_raw_default;
ANALYZE VERBOSE obsinity.events_raw;
ANALYZE VERBOSE obsinity.event_attr_index_default;
ANALYZE VERBOSE obsinity.event_attr_index;

DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT c.oid::regclass AS rel
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'obsinity'
      AND i.inhparent IN ('obsinity.events_raw'::regclass, 'obsinity.event_attr_index'::regclass)
  LOOP
    EXECUTE format('ANALYZE VERBOSE %s;', r.rel);
  END LOOP;
END $$;
```

## Outcome

### Before
- Typical execution around `~1.88s` to `~1.96s` for `limit=20`.
- In bad plans for larger limits, nested-loop re-evaluation produced extremely high join-filter discard counts.

### After index + stats stabilization
- Observed plans use:
  - `ix_events_raw_default_search_service_event_started_event_id` for base filtering/order seed.
  - `ix_events_raw_default_search_service_event_id` for final page row lookup.
- Representative execution times:
  - `limit=20`: about `~109ms`
  - `limit=200`: about `~93ms` to `~104ms`

## Current Interpretation
- Query path is now healthy and predictable for the investigated payload.
- Remaining cost is primarily proportional to number of matching events in the rolling 30-minute window (top-N sort over the base candidate set).

## Validation Checklist
1. Confirm Flyway `V6` applied in target environment.
2. Run `ANALYZE` commands above after migration/index build.
3. Re-run the payload and verify:
   - `Execution Time` remains in expected range.
   - Plans include both `ix_events_raw_default_search_service_event_started_event_id` and `ix_events_raw_default_search_service_event_id`.

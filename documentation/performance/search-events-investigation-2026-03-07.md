# Search Performance Investigation (March 7-8, 2026)

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

## Operational Maintenance Note
- If the database is cleaned/recreated and ingestion restarts from empty, run the `ANALYZE` commands again after data has accumulated.
- Treat this as ongoing maintenance, not a one-time action:
  - run after major data reloads/backfills
  - run after creating new indexes
  - run periodically for high-churn datasets to keep planner statistics current

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

## March 8 Recurrence (No Code Change Between Runs)
- A regression reappeared on March 8, 2026, then self-recovered minutes later:
  - `limit=1000`: `26963 ms`
  - `limit=200`: `5904 ms`
  - `limit=20`: `732-770 ms`
- In the regressed plans, `Rows Removed by Join Filter` reached extremely high values (for example `225,583,000`), indicating the planner had switched back to a pathological nested-loop shape.
- Later runs returned to the healthy indexed plan (`~96-99 ms`) without application code changes.

Interpretation:
- PostgreSQL planner behavior changed due to stats/selectivity/cache state, not because query text changed in the app at that moment.
- This confirmed we needed a stronger plan-shape guardrail, not only manual `ANALYZE`.

## Final Mitigations Implemented

### 4. Forced CTE materialization in search SQL
- File: `obsinity-service-core/src/main/java/com/obsinity/service/core/objql/OBJqlCteBuilder.java`
- Changed key CTEs to `MATERIALIZED` (`base`, `matched`, `matched_base`, `ordered`, `page`) to prevent planner inlining/re-evaluation paths that caused explosive join-filter loops.

### 5. Added scheduled automatic ANALYZE
- File: `obsinity-service-core/src/main/java/com/obsinity/service/core/impl/PartitionMaintenanceService.java`
- Added maintenance job:
  - `ANALYZE obsinity.events_raw_default`
  - `ANALYZE obsinity.event_attr_index_default`
- Defaults:
  - scheduled every 5 minutes
  - enabled on startup
- Config:
  - `obsinity.partition.maintenance.autoAnalyze.enabled=true`
  - `obsinity.partition.maintenance.autoAnalyze.onStartup=true`
  - `obsinity.partition.maintenance.autoAnalyze.cron=0 */5 * * * *`
  - `obsinity.partition.maintenance.cron=0 15 2 * * *`

## Current Interpretation
- Query path is now hardened against the observed planner instability.
- Remaining cost is primarily proportional to number of matching events in the rolling 30-minute window (top-N over the base candidate set), but catastrophic plan flips should be significantly less likely.

## Validation Checklist
1. Confirm Flyway `V6` applied in target environment.
2. Ensure the runtime includes:
   - `MATERIALIZED` CTE search SQL changes
   - scheduled auto-`ANALYZE` maintenance job
3. After clean DB/bootstrap or large reloads, run the manual `ANALYZE` commands above at least once.
4. Re-run the payload and verify:
   - `Execution Time` remains in expected range.
   - Plans include both `ix_events_raw_default_search_service_event_started_event_id` and `ix_events_raw_default_search_service_event_id`.
   - SQL logged by EXPLAIN includes `MATERIALIZED` on the key CTEs.

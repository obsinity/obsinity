# Issues Log

## Counter rollup deadlocks drop increments (critical)
- **Where**: `obsinity.event_counts` upserts during rollup (e.g., D7 partition) in `CounterPersistService`.
- **What**: Concurrent `INSERT ... ON CONFLICT DO UPDATE` batches deadlock and the batch is aborted. Events are already acknowledged, so the affected increments are lost, making counters inaccurate.
- **Evidence**: Deadlock between persist workers inserting into `event_counts_2025_49_d7` (SQLState 40P01) while running the demo generator.
- **Impact**: Silent undercounting for any bucket that hits a deadlock (all rollups for that batch). Counter data becomes untrustworthy under load/backfill.
- **Remediations to implement** (do all or equivalent):
  1) **Serialize rollups per bucket/key**: ensure only one worker writes a given `(bucket, ts)` at a time (e.g., shard the queue by bucket and use a single writer per shard, or guard with advisory locks).
  2) **Reduce contention scope**: split batches by bucket and commit separately; avoid mixing multiple rollup buckets in a single transaction.
  3) **Retries with jitter and max-attempts**: add deadlock retries around the batch (partial mitigation; already added basic retry but not sufficient alone—still seeing deadlocks that abort the transaction).
  4) **Backfill throttling**: lower persist worker count during backfills and/or temporarily disable high-level rollups (D7) when ingesting large historical windows.
  5) **Detection/alerting**: count deadlocks and surface them (metrics/log alerts) so lost increments are observable.
  6) **Fail-stop or requeue on deadlock**: ensure failed batches are retried from the queue instead of being dropped once the transaction aborts.

### Latest occurrence (still failing after retry)
- Timestamp: 2025-12-12T19:04:28Z
- Symptom: Deadlock on D7 (`event_counts_2025_49_d7`) followed by "current transaction is aborted" and batch drop; new retry logic did not recover.
- Action: Prioritize per-bucket serialization or advisory locks on D7/D1 rollups, and requeue on deadlock instead of dropping.

# Counter Rollup Deadlock Mitigation (event_counts)

Context: Concurrent `INSERT ... ON CONFLICT DO UPDATE` into `obsinity.event_counts` (especially D7/D1 rollups) can deadlock when multiple persist workers/processes target the same `(bucket, ts, counter_config_id, key_hash)`. Retries alone are not sufficient because batches are dropped on failure.

## Design options

1) **Advisory locks per bucket/timestamp**
   - Acquire a PostgreSQL advisory lock on `(bucket, ts, counter_config_id)` before upserting the rollup rows; release after the batch.
   - Pros: Cross-instance coordination, simple to reason about.
   - Cons: Can serialize throughput on hot buckets; need timeout/backoff to avoid thundering herd.

2) **Shard persist queue by rollup bucket**
   - Partition the persist queue per bucket (S5/M1/M5/H1/D1/D7) and ensure a single consumer per shard (per instance).
   - Pros: Reduces cross-bucket lock contention; simpler than fine-grained advisory locks.
   - Cons: Still allows contention across instances for the same bucket/ts; may need instance-level coordination or lock #1.

3) **Split transactions per bucket**
   - For a given batch, upsert each rollup bucket in its own transaction to narrow lock scope and reduce multi-bucket deadlocks.
   - Pros: Smaller deadlock surface.
   - Cons: More transactions; does not prevent same-bucket deadlocks across workers/instances.

4) **Fail-stop + requeue on deadlock**
   - Detect deadlock and requeue the batch (or leave in queue) instead of dropping after retries.
   - Pros: Prevents silent undercount; eventual consistency if retries succeed.
   - Cons: Needs idempotence or dedupe (keyed upserts help); can starve if contention persists.

5) **Throttle/shape ingestion for large rollups**
   - Reduce persist worker count during backfill; slice backfill time ranges; optionally disable high rollups (e.g., D7) during heavy historical loads.
   - Pros: Operationally simple; lowers contention probability.
   - Cons: Manual; slows ingestion; not a guarantee.

6) **Index/partition hygiene**
   - Ensure partitions exist and are attached; consider fillfactor on hot rollup partitions.
   - Pros: Reduces index page contention somewhat.
   - Cons: Minor compared to coordination fixes.

## Recommended path (stacked)
1. Implement **advisory locks per (bucket, ts, counter_config_id)** around rollup upserts with timeout + jittered retry.
2. Add **fail-stop/requeue** semantics on deadlock; do not drop batches after retry exhaustion.
3. **Split transactions per bucket** to narrow lock scope.
4. Operational guardrails: lower worker count during backfill or temporarily drop D7 rollup when loading historical data.

## Alternative idea: per-node staging + coordinator rollup
- Each node flushes 5s data into per-node rows (e.g., `(ts, bucket=S5, counter_config_id, key_hash, node_id)`).
- A coordinator job periodically aggregates per-node rows into the central 5s row and emits the higher-granularity rollups (M1/M5/H1/D1/D7).
- Pros: Greatly reduces direct contention between nodes; only the coordinator writes the shared rollup rows.
- Cons: Needs a coordinator election/leader; adds lag between ingest and rollup; per-node staging tables/columns required; failure of coordinator can delay rollups.

## Open questions
- Acceptable latency hit for D7/D1 rollups under advisory lock?
- Should we shard D7/D1 across instances (sticky partition ownership) instead of relying solely on locks?
- How to expose deadlock counts as metrics/alerts to ensure visibility?

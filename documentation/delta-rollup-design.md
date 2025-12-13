# Incremental Delta Rollup Design

## Goals
- Continuously propagate time-series/counter/histogram data from the smallest granularity upward without waiting for full windows.
- Reduce contention and deadlocks by operating in small, ordered batches and avoiding long transactions.
- Bound staleness with periodic rollup passes while preserving correctness (no double-apply).

## Core Idea
Treat each flush from the smallest granularity (e.g., 5s) as a **delta set**. Higher granularities consume these deltas incrementally:
- Persist each fine-grained flush as an immutable delta record.
- Maintain watermarks per granularity to track which deltas have been consumed.
- Roll up the next granularity by aggregating all unconsumed deltas that fall into its window, apply a single upsert per target bucket/key, then advance the watermark.
- Repeat the same pattern up the chain (5s → 1m → 5m/1h → 1d → 7d).

## Data Structures
- **Delta store** (logical): existing event_counts / histograms tables already hold per-epoch/key increments. Treat each inserted row as a delta for its granularity.
- **Watermarks**: per (serviceId, eventTypeId, metricType, granularity, keyHash?):
  - `lastConsumedEpoch` (the latest fine-grain epoch fully applied to the next granularity).
  - Optionally a `lastConsumedId` if we introduce sequence ids instead of pure time.
- **Rollup cursor**: derived from watermark; next rollup reads `(epoch > lastConsumedEpoch AND epoch <= rollupWindowEnd)`.

## Rollup Flow (per metric type)
1. **Collect**: Select all fine-grain rows for granularity G where `epoch` is greater than the watermark and <= the current rollup boundary for granularity G+1.
2. **Aggregate**: Group by target (bucket at G+1, keyHash, keyData, config ids) and sum counters / merge histogram bins.
3. **Apply**: Upsert aggregated rows into the coarser granularity table. One transaction per target bucket is preferred.
4. **Advance watermark**: Set watermark to the max `epoch` fully applied. This makes rollup idempotent and prevents double-count.
5. **Repeat upward** for each granularity pair.

## Ordering and Contention Control
- Always process buckets in deterministic order: smallest → largest granularity; within a granularity, order by `(bucket, ts, keyHash)`.
- Use short transactions scoped to a single target bucket (or a small batch of buckets) to minimize lock duration.
- Keep existing retry-with-jitter for deadlocks; ordering should already reduce them drastically.
- Avoid coarse advisory locks in the hot path; consider them later for multi-instance coordination.

## Scheduling / Staleness Bounds
- Run a periodic rollup task per granularity (e.g., every 5–15s for 5s→1m, every 30–60s for 1m→5m/1h).
- Also trigger rollup when backlog exceeds a threshold (watermark lag or row count).
- This yields bounded staleness without waiting for a “complete window.”

## Failure & Idempotency
- If apply fails mid-batch, the watermark is not advanced; the next run will re-read the same deltas and re-apply safely because aggregation is done before upsert and watermark only moves after success.
- Log and surface persistent rollup failures (after retries) as operational alerts; counters/histograms correctness depends on eventual success.

## Consistency Model
- **Eventual consistency** across granularities: lower levels visible immediately; higher levels catch up on the next rollup pass.
- **Monotonic**: counts only increase; no double-apply because watermark gates consumption.
- **No global transaction** across levels; each level converges independently.

## Performance Expectations
- Smaller batches and deterministic ordering reduce contention.
- Watermark-driven rollups avoid reading already-applied deltas.
- Coarser levels update continuously, avoiding large “catch-up” spikes.

## Open Questions / Extensions
- **Watermark granularity**: per keyHash vs per service/event. Per keyHash reduces over-aggregation but adds metadata volume.
- **Storage of consumed deltas**: keep all fine-grain rows, or mark them consumed for cleanup/archival.
- **Multi-instance coordination**: may later add advisory locks per (granularity, bucket) or a leader election for rollup workers.
- **Backfill**: allow rollup to start from an arbitrary historical watermark for replay/backfill jobs.

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

## Query-Time Range Stitching

Rollup levels are only half the design — answering `SUM(counter) WHERE ts BETWEEN a AND b` for an
**arbitrary** range requires combining multiple granularities at read time. This is not yet
implemented; the plan below describes the intended approach so the read path has a documented
target instead of an implicit gap.

### Decomposition algorithm
Given a query range `[a, b)` and the available granularities (5s, 1m, 5m, 1h, 1d, 7d, ordered
coarsest-first), greedily cover the range from the outside in:
1. **Align to the coarsest granularity that fits**: find the largest bucket size `G` such that at
   least one full `G`-bucket lies inside `[a, b)`. Take all full `G`-buckets covering the "middle"
   of the range as a single aggregated read (one row per bucket, summed).
2. **Recurse on the two remainders**: the partial interval before the first full `G`-bucket
   (`[a, firstFullBucketStart)`) and after the last (`[lastFullBucketEnd, b)`) are each too short
   to contain a full `G`-bucket, so recurse into the next-finer granularity for those edges.
3. **Bottom out at 5s** (or at raw events, if the range is shorter than one 5s bucket).
4. **Merge**: sum the coarse middle plus the two recursively-resolved edges into one result. For
   histograms, this is a bin-wise merge rather than a scalar sum.

This mirrors interval-covering by binary/decimal place value: e.g., a 9-day range is covered by
`1×7d + 2×1d`, not 129,600 five-second buckets. In the worst case the number of buckets read is
`O(k)` per granularity level times the number of levels (6), i.e. **bounded by a small constant**
(≤ ~12–18 buckets touched at the edges) **regardless of the total range length**, versus `O(range /
5s)` for a naive scan of the finest granularity.

### Why this matters for performance
- A naive implementation that always scans the 5s (or raw event) table for any range is `O(n)` in
  range length: a 7-day query touches ~120,960 rows at 5s resolution. The decomposition above
  touches at most a handful of 7d/1d rows plus a small constant number of edge buckets — effectively
  `O(1)` relative to range length once the range exceeds a few coarse-bucket widths.
- Correctness depends on the **same watermark boundaries** used for rollup: a coarse bucket may only
  be used in the "middle" aggregate once rollup has advanced its watermark past that bucket's end.
  If the watermark for `G` hasn't reached a bucket yet, that bucket must be treated as not-yet-full
  and folded into the recursive edge case (i.e., computed from the next-finer granularity, or from
  deltas not yet rolled up) — otherwise the query would silently undercount recent data.
- This makes the query planner watermark-aware, not just bucket-boundary-aware: "is this bucket
  closed" is a function of the granularity's watermark, not merely `bucketEnd <= now`.

### Status
Not implemented today. `documentation/configuration/counters.md` only documents aligning a
*requested interval* to a single supported bucket size, not decomposing an arbitrary range across
mixed granularities. This section is a design target for that read path.

## Comparison to MongoDB Metrics Aggregation

MongoDB has no directly equivalent built-in feature; the closest analogs are:

| Aspect | Obsinity (this design) | MongoDB |
|---|---|---|
| Precomputed multi-resolution rollups | First-class: dedicated bucket tables per granularity (5s/1m/5m/1h/1d/7d), maintained continuously via watermark cascade | Not built in. Equivalent behavior requires hand-rolled scheduled aggregation pipelines (`$group` by `$dateTrunc`, written out via `$merge` into separate rollup collections per granularity) |
| Incremental/streaming rollup | Watermark-gated, delta-based, idempotent, low-latency (5–15s cadence) | No native incremental aggregation primitive; `$merge`-based rollup jobs are typically run as periodic batch jobs (cron/Atlas Scheduled Triggers), not continuously watermarked. Change streams can approximate incremental triggers but the app must implement the same watermark/idempotency logic itself |
| Native time-series storage | N/A (uses relational bucket tables) | Time-series collections (5.0+) auto-bucket raw measurements by `granularity`/`bucketMaxSpanSeconds`, but this is a storage/compression optimization at *one* resolution — it does not produce queryable pre-aggregated sums at multiple resolutions the way this design does |
| Query-time range decomposition (stitching granularities) | Designed above, not yet implemented | Not provided by MongoDB either. If you build your own rollup collections, you must write the same coarse-middle/fine-edges stitching logic in application code — MongoDB's aggregation pipeline has no cross-collection, multi-granularity query planner |
| Consistency model | Eventual, monotonic, per-level convergence via watermarks (no cross-level transaction) | Eventual as well, but consistency depends entirely on how the `$merge` jobs are scheduled and deduplicated; no built-in watermark concept, so idempotency (avoiding double-counting on rerun) must be hand-built (e.g., via a `_lastProcessedTs` field checked by the pipeline) |

### Expected performance comparison
- **Point/short-range queries** (fits in one or two coarse buckets): comparable. Both designs hit a
  single small pre-aggregated document/row; latency is dominated by index lookup, not aggregation
  work. Roughly parity, single-digit milliseconds either way.
- **Wide-range queries without rollups** (naive MongoDB aggregation pipeline over raw/time-series
  data using `$group`+`$dateTrunc` computed at query time): scales with the number of raw documents
  in range — `O(n)`. For a 7-day range at sub-minute granularity this can mean scanning millions of
  documents per query; MongoDB's time-series collections mitigate this somewhat via columnar-style
  storage and block-level statistics, but it is still fundamentally a scan-and-aggregate, not an
  `O(1)`-ish bucket lookup.
- **Wide-range queries with hand-built MongoDB rollup collections**: performance converges to the
  same order as this design (`O(k)` bucket reads via the coarsest fitting collection), *provided* the
  application implements the same coarse-middle/fine-edges stitching described above. MongoDB does
  not do this automatically — the performance parity is only achievable by reimplementing this
  document's decomposition algorithm on top of MongoDB's `$merge` rollup collections.
- **Net expectation**: this design's advantage over vanilla MongoDB usage is not the storage engine,
  it's that the watermark-cascade rollup and (once implemented) range-decomposition query planner are
  first-class and automatic, whereas the equivalent MongoDB setup requires building and maintaining
  both pieces (scheduled `$merge` rollups + a custom multi-collection query planner) as bespoke
  application code with no framework guarantees around idempotency or watermark-consistent bucket
  closure.

## Open Questions / Extensions
- **Watermark granularity**: per keyHash vs per service/event. Per keyHash reduces over-aggregation but adds metadata volume.
- **Storage of consumed deltas**: keep all fine-grain rows, or mark them consumed for cleanup/archival.
- **Multi-instance coordination**: may later add advisory locks per (granularity, bucket) or a leader election for rollup workers.
- **Backfill**: allow rollup to start from an arbitrary historical watermark for replay/backfill jobs.
- **Range-decomposition query planner**: implement the "Query-Time Range Stitching" algorithm above (currently a design target, not code).

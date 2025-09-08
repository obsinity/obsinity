# Ingestion & Processing Flow

Ingestion is where theory meets reality. Obsinity is designed to handle **10k–50k transactions per second** without breaking a sweat.

## Flow
1. Accept events in batches — mixed types are welcome.
2. Validate and normalize — no dirty data slips through.
3. Protect — apply masking rules immediately.
4. Persist — store events in their rightful partitions.
5. Maintain metrics — update counts, histograms, gauges.
6. Roll up — coarser windows are created on flush, ensuring efficiency.

## Why It Matters
Other systems push complexity downstream, leaving operators with fragile batch jobs. Obsinity bakes rollups directly into the ingestion flush path. The result: **fast queries, predictable costs, and no surprise lag**.


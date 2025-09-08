# Operations & Performance

Performance is not optional. Obsinity is engineered for **predictable high throughput**.

## Targets
- Ingestion: 50k–150k events/sec.
- Query latency: p95 in line with rollup granularity.

## Background Jobs
Lightweight: partition maintenance and index checks only. No heavy post-processing.

## Scaling Philosophy
Scale **vertically first**. Fine-grained in-memory counters keep ingestion efficient. Only when limits are hit does Obsinity shard by service or time. This minimizes operational complexity while providing a path to scale.

## Why It Matters
Too many systems force premature sharding, creating complexity long before it’s needed. Obsinity delays that pain, letting teams grow naturally.


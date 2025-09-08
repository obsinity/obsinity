# Storage & Partitioning

Storage is where many observability systems stumble. Obsinity treats it as a first-class design concern.

## Partitioning Strategy
Flexibility is key. Obsinity allows partitioning by month, week, or day — and always by service. This localizes IO, minimizes bloat, and makes queries predictable.

## Governance
Event schemas live in a registry. Required attributes, optional ones, and indexes are declared up front. No JSONB chaos, no guessing games. This is about **discipline at scale**.

## Why It Matters
In an enterprise, data chaos quickly becomes compliance chaos. Obsinity’s explicit schema governance ensures the platform stays clean, auditable, and performant — no matter how many teams adopt it.


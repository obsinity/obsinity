# Logical Architecture

Obsinity’s architecture is intentionally simple yet strategically powerful. It’s built to be **understandable by developers and operators alike**, while scaling to enterprise demands.

## Producers / SDKs
Why burden developers with boilerplate telemetry code? Obsinity’s SDK does the heavy lifting. With a handful of annotations, engineers can express flows, steps, and events. The SDK enforces OTEL-shaped envelopes, ensuring data consistency without developer pain.

## Ingestion Gateway
The gateway acts as the bouncer at the door: **every event must be valid, consistent, and safe before entry**. It validates schemas, enforces `service.id`, and applies masking rules. By catching issues here, Obsinity ensures downstream storage remains clean, reliable, and compliant.

## Persistence (PostgreSQL)
The beating heart is PostgreSQL, structured into clear tables:
- `events_raw`: canonical log of truth.
- `event_counts_*`: counters across buckets (5s–7d).
- `event_histograms_*`: latency and size distributions.
- `event_gauges_*`: current value snapshots.

This design balances fidelity (raw events are never lost) with performance (pre-aggregated metrics). Enterprises get the best of both worlds.

## Query & API
Data isn’t valuable unless it’s explorable. Obsinity exposes both **event queries** (for detailed forensics) and **metric queries** (for trends and dashboards). Flow views tie start and finish events into meaningful execution stories.

## Management & Tooling
Governance is not an afterthought. With OB-SQL, admins can define event types, set retention policies, enforce masking, and configure indexes — all declaratively. This makes Obsinity a first-class citizen in enterprise data governance practices.

## Visualization
Obsinity acknowledges reality: enterprises already use tools like Grafana. That’s why Obsinity is SQL-native and Grafana-friendly out of the box. But it also invests in its own **dashboard tiles**, offering a DevX-inspired native UX for counters, gauges, and flow KPIs. External flexibility + native polish = pragmatic adoption.


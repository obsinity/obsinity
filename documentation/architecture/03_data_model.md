# Data Model

The data model is Obsinity’s crown jewel. It balances **flexibility for developers** with **rigidity for performance**.

## Event Envelope
Obsinity mirrors OpenTelemetry, meaning developers don’t have to learn a new language. Every event carries a `service.id`, trace identifiers, timestamps, outcomes, and attributes. This makes each event a self-contained truth capsule.

## Metrics
Metrics are not dumb counters. They are **expressions over events**:
- Counters tell you how often something happened.
- Gauges tell you the latest state.
- Histograms show you the shape of performance.

Because they’re derived from raw events, metrics always have provenance. You never lose the "why" behind the number.

## Dimensions & Indexing
Obsinity is opinionated: only explicitly marked attributes are indexed. This forces teams to think about what’s truly important, while protecting the database from query sprawl. Unlike JSONB free-for-alls, Obsinity favors **intentional governance over accidental complexity**.

## Retention & Masking
The model enforces trust. Sensitive attributes can be dropped or hashed. Retention is configurable per event type. This makes the data model not just performant, but also **ethically and legally sound**.


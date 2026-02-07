# State Extractors: Turning Events into State Counts and Transitions

Obsinity treats **all incoming data as generic events**. Higher-level behavior—like **state counts** and **state transitions**—is *layered on top* of those events via configuration. This document explains how state extraction works and why we apply this pattern broadly.

## Core Idea

- Events are the primitive.
- State is derived, not special-cased.
- Configuration defines how to interpret events as state updates.

This means the same event stream can power multiple state models (and other derived views) without changing producers or core ingest pipelines.

## How State Extraction Works

State extraction is configured per service and event name. When an event arrives:

1. Obsinity looks up **state extractors** for the event name.
2. For each extractor:
   - It reads the **object id** from a configured attribute path.
   - It reads one or more **state attributes** from configured attribute paths.
   - It updates the current snapshot and increments/decrements state counts.
   - It records state transitions when the state value changes.

If the configured object id is missing or blank, the extractor is skipped for that event.

### Example Configuration

See:
- `obsinity-reference-service/src/main/resources/service-definitions/services/payments/state-extractors.yaml`

Example excerpt:

```yaml
service: payments
stateExtractors:
  - rawType: user_profile.updated
    objectType: UserProfile
    objectIdField: user.profile_id
    stateAttributes:
      - user.status
```

This means:
- An event named `user_profile.updated` represents a `UserProfile` state update.
- The user profile id is read from `user.profile_id`.
- The current state is read from `user.status`.

## Multiple Object Types Per Event

A single event name can drive multiple state extractors. For example, you can model two different object types from the same event:

- Extractor A: `objectType: ObjectA`, `objectIdField: a.id`, `stateAttributes: [a.status]`
- Extractor B: `objectType: ObjectB`, `objectIdField: b.id`, `stateAttributes: [b.state]`

If both attribute paths exist in the event, Obsinity updates both objects.

## State Counts and Transitions Are Derived

State **counts** and **transitions** are not first-class event types—they are derived views created by the state extraction layer:

- **State counts**: current distribution of objects by state.
- **State transitions**: transitions between states over time.
- **Timeseries snapshots**: periodic snapshots of counts for Grafana dashboards.

These are built from generic events without requiring separate event schemas or services.

## Why This Pattern Matters

We intentionally use **derived layers** across the system:

- Generic events → state updates
- Generic events → counters
- Generic events → histograms

This keeps ingestion simple, avoids schema coupling, and allows new derived features without changing producers. It’s a core design principle of Obsinity.

## Operational Notes

- If the object id is missing, the extractor does nothing for that event.
- If the event name has no extractor configuration, it is treated as a normal event only.
- State extraction runs during ingest, not as a separate pipeline.

## Related Files

- `obsinity-reference-service/src/main/resources/service-definitions/services/payments/state-extractors.yaml`
- `obsinity-service-core/src/main/java/com/obsinity/service/core/state/StateDetectionService.java`
- `obsinity-service-core/src/main/java/com/obsinity/service/core/state/timeseries/StateCountTimeseriesJob.java`

# RFC: Unified Event-Centric Configuration

This folder contains draft materials for a unified config model centered on events, with generic service-level materializations.

## Files
- `demo-config-mirror.yaml`: Proposed unified config shape mirroring the current demo setup.

## Core Sections
- `spec.events`: Event definitions, schema constraints, and event-time processors.
- `spec.materializations`: Generic persisted views/rollups built from platform sources.

## Important Design Point
- Not all configuration can be event-centric.
- Event-centric config is the right place for schema, indexing, and event-time processors.
- Platform-level concerns such as time-series materialization cadence, rollup buckets, and retention are cross-event concerns and belong in generic service-level config (`spec.materializations`).

## Event Processors
- `type: index`: Declares dimensions/attributes to index for filtering and grouping.
- `type: object-state-projection`: Projects event attributes onto an object instance state model.
- `type: counter`: Increments a named counter by configured dimensions.
- `type: histogram`: Updates a named histogram from a numeric value path.

### Why `counter` and `object-state-projection` both exist
- They answer different questions and are not duplicates.
- `object-state-projection` maintains object lifecycle semantics:
  - current state per object instance,
  - state history,
  - state count updates on change (increment new/decrement previous),
  - state transition updates (`from -> to`).
- `counter` captures event activity semantics:
  - counts each event occurrence by dimensions (for example `user.status`, `dimensions.channel`),
  - includes repeated events even when state does not change,
  - supports throughput/traffic analysis independent of lifecycle transitions.

## Object State Projection
- `target.objectType`: Logical object class (for example, `UserProfile`).
- `target.objectId`: Attribute path used as object instance ID.
- `states[]`: Mapping from event attributes to tracked state fields.
- `actions`: Side effects applied on state changes.

### Supported Actions (Draft)
- `state-history`: Stores per-object state history over time.
- `state-count`: Increments new state count and decrements previous state count.
- `state-transition`: Increments transition counts for `from_state -> to_state`.

### Transition Policy (Draft)
- `transitionPolicy.only` restricts which previous states contribute transitions.
- `transitionPolicy.additional` adds extra previous states on top of the normal latest previous-state transition.
- Tokens:
  - `"?"`: latest previous state
  - `"*"`: all previous states
  - any other value: explicit state name
- Rules:
  - missing `transitionPolicy` means normal latest previous-state transitions are emitted
  - `additional` augments that default behavior
  - `only` disables default behavior and uses only the configured tokens
  - if `"*"` is present in `only` or `additional`, it overrides the rest for that mode

### Retention Note (Current Direction)
- State history is not subject to retention in this draft (`retention: none`).
- State counts materialization is also not subject to retention in this draft.

## Retention Domains (Important)
- Event retention, state history retention, and materialization retention are different concerns and should be configured independently.
- Event retention controls how long raw source events remain available.
- State history retention controls how long per-object lifecycle history is preserved.
- Materialization retention controls how long derived rollups are kept.

### Operational Consequence
- If event retention is short, we lose replay/backfill capability.
- Once source events are expunged, we cannot reliably:
  - regenerate lost derived metrics,
  - define new derived metrics and backfill them from historical events.

## Obsinity Positioning (Draft)
- Obsinity is optimized for long-term event retention and long-term derived metrics.
- A core capability is defining new derived metrics after ingestion and building them from retained historical events.
- Some data should support indefinite retention (until explicit deletion), especially:
  - object state history,
  - object state counts (unless we intentionally retire those counts).

## Materializations
- `kind: timeseries`: Periodic persisted rollups.
- `source.kind: object-state-counts`: Source stream of current state counts.
- `source.kind: object-state-transitions`: Source stream of transition counts.
- `every`: Materialization cadence.
- `buckets`: Bucket granularities to persist.
- `retention.ttl` (optional): Data retention period where retention applies.

## Notes
- This is an RFC draft shape, not runtime implementation.
- Names and field structure are intentionally explicit to reduce ambiguity in review and onboarding.

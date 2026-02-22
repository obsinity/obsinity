# Obsinity Backend Flow

## Unified Event-Centric Configuration (Action Model)

Obsinity treats **events as the only primitive**.
Indexes, state, transitions, counters, histograms, and time series are all produced by executing **actions** defined in a `UnifiedServiceConfig`.

There are only two trigger types:

1. **Event-triggered actions** (`spec.events[].actions[]`)
2. **Scheduled actions** (`spec.schedules[].actions[]`)

Everything in the backend is an action.

---

# 1. Configuration Model

```yaml
apiVersion: obsinity/v1alpha1
kind: UnifiedServiceConfig
metadata:
  service: payments
  profile: demo
```

The config is scoped to:

* **service** → logical application boundary
* **profile** → deployment profile (demo, prod, etc.)

---

# 2. Event-Triggered Actions

Each event definition declares:

* raw event retention
* minimal schema validation
* ordered list of actions

```yaml
spec:
  events:
    - name: user_profile.updated
      retention:
        ttl: 30d
      schema:
        required:
          - user.profile_id
          - user.status
      actions:
        - type: index
        - type: state-machine
        - type: counter
        - type: histogram
```

---

# 3. Runtime Flow (Event Path)

When an event arrives:

## Step 1 — Ingress & Normalize

* Resolve `service` and `eventName`
* Resolve **event time**
* Assign or validate `eventId`
* Compute canonical event hash (used for idempotency)

## Step 2 — Schema Validation

All fields listed under `schema.required[]` must exist.

If validation fails:

* Event is rejected or marked invalid (policy-defined)
* No actions execute

## Step 3 — Idempotency Check

Obsinity enforces idempotency using:

* `eventId`
* canonical event payload hash

### Duplicate Handling Rules

1. If `eventId` is new:

    * Store raw event
    * Store event hash
    * Execute actions

2. If `eventId` already exists:

    * Hash incoming payload
    * Compare with stored hash

        * If hashes match → **no-op** (safe replay)
        * If hashes differ → write to **error store**

### Error Store

If duplicate `eventId` has modified payload:

* Store original hash
* Store new hash
* Store both payloads (or diff metadata)
* Flag as integrity violation

This protects against:

* producer bugs
* replay corruption
* accidental ID reuse

---

# 4. Time Semantics

## Event Time vs Ingest Time

Obsinity **always uses event time for bucketing and state semantics.**

Ingest time is never used for:

* metric bucket assignment
* state transitions
* state count updates
* histogram aggregation
* materializations

### Bucketing Rule

```
bucket = floor(event_time, granularity)
```

Example:

* Event time: `10:03:17`
* 5m bucket → `10:00`
* 1h bucket → `10:00`
* 1d bucket → `00:00`

Late events update historical buckets.

---

# 5. Action Types

---

## 5.1 `type: index`

Purpose: create searchable/indexable dimensions.

```yaml
- type: index
  dimensions:
    - user.profile_id
    - user.status
    - dimensions.channel
    - dimensions.region
    - user.tier
```

Behavior:

* Extract dimension values
* Write index entry for filtering/grouping
* No state mutation

---

## 5.2 `type: state-machine`

Purpose: manage object lifecycle state and emit state facts.

```yaml
- type: state-machine
  target:
    objectType: UserProfile
    objectId: user.profile_id

  state:
    name: lifecycle
    valueFrom: user.status

  transitionPolicy:
    additional: ["NEW"]

  emit:
    history:
      enabled: true
      retention: none
    count:
      enabled: true
    transition:
      enabled: true
```

### Behavior

1. Resolve object identity:

   ```
   UserProfile/<profile_id>
   ```

2. Read current snapshot state

3. Extract new state from `valueFrom`

4. Apply transition policy

5. Update snapshot

6. Emit optional fact streams:

    * state history
    * state count deltas
    * transition facts

---

## State-Machine Transition Semantics

### Configured Transitions

Configured transitions determine which transition time series are explicitly materialized.

### Default Behavior

If a transition does not match a configured transition:

* It is still counted
* It is recorded as `previous_state → new_state`

Transitions are never silently dropped.

---

### Rapid / Out-of-Order Transition Caveat

If multiple transitions occur in a short time window or arrive out of order, “previous state” may be ambiguous.

This requires an explicit strategy, such as:

* Strict monotonic event-time ordering per object
* Small window reordering buffer
* Last-write-wins with transition logging
* Conflict detection + warning emission

A formal strategy must be defined for production correctness.

---

## Stores Produced by `state-machine`

| Store                         | Purpose                   |
| ----------------------------- | ------------------------- |
| object_state_snapshot         | current state per object  |
| object_state_history          | append-only changes       |
| object_state_counts_fact      | increment/decrement facts |
| object_state_transitions_fact | transition facts          |

Fact stores are append-only.

---

## 5.3 `type: counter`

Purpose: increment metric time series.

```yaml
- type: counter
  name: profile_updates_by_status
  dimensions:
    - user.status
    - dimensions.channel
  rollup:
    granularities: [5s, 1m, 5m]
```

Behavior:

* Increment bucket determined by event time
* Late events update historical buckets

---

## 5.4 `type: histogram`

Purpose: record numeric observations and compute percentiles.

```yaml
- type: histogram
  name: user_profile_update_duration_ms
  value: timings.duration_ms
  dimensions:
    - dimensions.channel
    - dimensions.region
  rollup:
    granularities: [5s, 1m, 5m, 1h]
    percentiles: [0.5, 0.9, 0.95, 0.99]
```

Behavior:

* Extract numeric value
* Update histogram bucket
* Compute configured percentiles

---

# 6. Scheduled Actions

Scheduled actions are time-triggered.

```yaml
spec:
  schedules:
    - name: user_profile_state_counts
      every: 5s
      actions:
        - type: materialize
```

---

## 6.1 `type: materialize`

Purpose: convert fact stores into time series.

### State Counts

```yaml
- type: materialize
  name: user_profile_state_counts
  kind: timeseries
  source:
    kind: object-state-counts
    objectType: UserProfile
    attribute: user.status
  buckets: [1m, 5m, 1h, 1d]
```

### State Transitions

```yaml
- type: materialize
  name: user_profile_state_transitions
  kind: timeseries
  source:
    kind: object-state-transitions
    objectType: UserProfile
    attribute: user.status
  buckets: [5s, 1m, 5m, 1h, 1d, 7d]
  retention:
    ttl: 730d
```

Materialization always uses **event time**.

---

# 7. Out-of-Order Handling

Out-of-order arrival does not affect bucket correctness.

Rule:

* Derivations are written into the bucket derived from event time.
* Database updates occur during flush.

Late events:

* Update historical rollups
* Do not corrupt time series

State-machine ordering strategy must be explicitly defined for high-frequency transitions.

---

# 8. Dimension Cardinality

Dimension cardinality is a configuration responsibility.

Engineers must:

* Avoid unbounded dimensions (e.g., user IDs)
* Validate expected cardinality in lower environments
* Monitor unique dimension growth

Recommended practices:

* Estimate cardinality explosion:
  `cardinality(dim1) × cardinality(dim2) × ...`
* Enforce guardrails in non-production
* Alert on abnormal series growth

---

# 9. Query Model

| Query Type                  | Backing Store                |
| --------------------------- | ---------------------------- |
| Raw event search            | raw events + index           |
| Current state               | object_state_snapshot        |
| State counts over time      | timeseries_state_counts      |
| State transitions over time | timeseries_state_transitions |
| Counters                    | metric_counter_rollup        |
| Histograms                  | metric_histogram_rollup      |

---

# 10. Guarantees

Obsinity guarantees:

* Event time is always authoritative.
* Idempotency is enforced by `eventId` + hash verification.
* Modified duplicates go to an error store.
* Rollups are mutable (late events update history).
* Fact stores are append-only.
* Transitions are always counted.
* Cardinality must be engineered intentionally.

---

# 11. Architectural Summary

Obsinity backend consists of:

1. Event ingestion layer
2. Action execution engine
3. Fact stores (state, transitions, counts)
4. Metric rollup stores
5. Scheduled materialization engine
6. Query layer

All system behavior is declared via:

```
events[].actions[]
schedules[].actions[]
```

There are no separate state configs, metric configs, or transition configs.

Everything is an action.

---

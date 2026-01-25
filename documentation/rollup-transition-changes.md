# Transition Rollup Changes (Initial-State + Resolved Rates)

## Executive Summary
- The first observed state now counts as a transition from `null`, stored as `(init)` in rollups.
- Transition counters can include `null` in `from:` to capture immediate terminal outcomes.
- Resolved-only transition ratios are computed from `from -> terminal` rollup series in the time window; they are event-based, not cohort-based.
- No state names are fixed; all state values come from per-object-type configuration.

## Scope
This document describes the changes on this branch related to state-transition rollups, the design decisions behind them, and how to use the new behavior for resolved-only rates such as completion/failure.

## Summary of Changes
- **Initial-state transitions**: The first observed state for an object is treated as a transition from `null` (no prior state) to the new state. This is now a first-class transition in the rollup pipeline.
- **Storage normalization**: `null` from-state is stored as a sentinel string `(init)` in rollup keys to satisfy non-null storage constraints while preserving semantics.
- **Config support**: Transition counter `from:` lists can include `null` to explicitly count initial transitions.
- **Evaluator behavior**:
  - `DEFAULT_LAST` emits `null -> newState` on first sight of an object.
  - `SUBSET` allows matching `null` when `from: [null, ...]` and the object has no prior state.
- **Rollup query helper**: The resolved rollup query accepts `fromState` as `null` and maps it to the sentinel at query time.
- **Tests**: Added/updated tests to verify initial-state behavior and config acceptance.

## Design Decisions
1) **Initial state as a real transition**
   - Objects are identified as "new" when there is no snapshot for the object.
   - The transition `null -> newState` is emitted so that "immediate terminal" outcomes are counted correctly (e.g., payment attempts that immediately fail).

2) **Sentinel storage for `null` from-state**
   - Rollup storage columns require `NOT NULL` keys. To preserve transitions from `null`, a sentinel `(init)` is used.
   - The sentinel is used only for storage/rollup keys. Query helpers accept `null` and normalize to `(init)` internally.

3) **No fixed state names**
   - The system does not assume `STARTED`, `FINISHED`, or `ABANDONED` are universal.
   - Transition counters and rollup queries are driven by the configured state names per object type.

4) **Resolved-only rollup ratios (event-based)**
   - Resolved transition ratios computed from rollups are **resolved-only** and **event-based**.
   - These ratios represent counts of `fromState -> toState` transitions in a time window; they do not represent per-object conversion funnels.

## Expectations and Semantics
- **Transition rollups** count events, not objects. This is correct for resolved-only rates where only terminal events in a window matter.
- **Immediate terminal**: If an object’s first observed state is a terminal state, the `null -> terminal` transition is emitted and counted.
- **Idempotency**: Posting IDs include event id, metric key, and bucket start. Duplicate deliveries do not double count.
- **Replacement**: If a synthetic terminal is superseded by a real terminal, the synthetic rollup increment is reversed at the synthetic timestamp, and the real terminal increment is applied at its timestamp.

## How to Use This for Rates
### Resolved Transition Ratios (Resolved-Only)
Resolved-only ratios are computed from terminal rollups within the query window:

```
resolved_transition_ratio(window) =
  count(fromState -> successTerminal in window)
  -------------------------------------------------
  count(fromState -> successTerminal in window)
  + count(fromState -> failureTerminal in window)
```

- **`fromState`** is whatever your configuration defines (e.g., a specific entry state, or `null` for initial-state outcomes).
- **`successTerminal` / `failureTerminal`** are the terminal states relevant to the object type.
- The rollup timestamp is the **terminal event timestamp**.

### Example Config (terminal rollups)
Use counter rules that express specific `from -> to` transitions:

```
transitionCounters:
  - name: entry_to_success
    objectType: Payment
    from: [ENTRY_STATE]
    to: SUCCESS_STATE
  - name: entry_to_failure
    objectType: Payment
    from: [ENTRY_STATE]
    to: FAILURE_STATE

  # Optional: track immediate terminal outcomes
  - name: initial_to_failure
    objectType: Payment
    from: [null]
    to: FAILURE_STATE
```

### Example Query Helper
Use `TransitionResolvedRollupQueryService`:
- `getResolvedTransitionSummary(serviceId, objectType, attribute, counterName, fromStates, toStates, bucket, windowStart, windowEnd)`
- `getResolvedTransitionSummary(serviceId, objectType, attribute, counterName, fromStates, toStates, bucket, windowStart, windowEnd, groupByFromState)`
- `getResolvedTransitionSummary(serviceId, objectType, attribute, counterNamesByToState, fromStates, toStates, bucket, windowStart, windowEnd, groupByFromState)`

`fromState` may be `null` to represent initial-state transitions.

## What This Feature Enables
- **Resolved transition ratios**: Success vs failure for terminal outcomes based purely on rollups.
- **Failure-rate reporting**: Count failures (explicit or inferred) within a window, optionally filtered by `fromState`.
- **Immediate-failure visibility**: Track cases where objects terminate without a prior known state (null -> terminal).

## Known Limitations
- Rollup rates are **event-based**, not per-object funnels. Use the object-based funnel APIs for cohort conversions.
- If a counter uses `from: "*"` it fans out by design; resolved-only rates should avoid `from: "*"` unless explicitly desired.
- Sentinel `(init)` is an internal storage detail; use `null` at the API layer for initial transitions.

## Files Touched (Primary)
- `obsinity-service-core/src/main/java/com/obsinity/service/core/state/transition/counter/TransitionCounterMetricKey.java`
- `obsinity-service-core/src/main/java/com/obsinity/service/core/state/transition/counter/TransitionCounterPostingService.java`
- `obsinity-service-core/src/main/java/com/obsinity/service/core/state/transition/counter/TransitionCounterEvaluator.java`
- `obsinity-service-core/src/main/java/com/obsinity/service/core/config/ConfigMaterializer.java`
- `obsinity-service-core/src/main/java/com/obsinity/service/core/state/query/TransitionResolvedRollupQueryRepository.java`
- `obsinity-service-core/src/main/java/com/obsinity/service/core/state/query/TransitionResolvedRollupQueryService.java`
- `documentation/rollup-transition-changes.md`

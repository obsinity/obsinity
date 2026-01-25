# Rollup Completion Rate Analysis

## 1) Where are state transitions evaluated and rollup postings emitted?
- Transition evaluation: `obsinity-service-core/src/main/java/com/obsinity/service/core/state/transition/counter/TransitionCounterEvaluator.java`
- Rollup postings: `obsinity-service-core/src/main/java/com/obsinity/service/core/state/transition/counter/TransitionCounterPostingService.java`
- Rollup storage: `obsinity-service-core/src/main/java/com/obsinity/service/core/state/transition/counter/JdbcTransitionCounterRollupRepository.java`

## 2) How is seenStates tracked and accessed at terminal time?
- Per-object snapshot: `obsinity-service-core/src/main/java/com/obsinity/service/core/state/transition/counter/TransitionCounterSnapshot.java`
- Stored as bitset + JSON for compatibility in `obsinity.object_transition_snapshots` (see `V8__transition_seen_states.sql`).
- Accessed in evaluator via `SeenStates` + `StateCodec` for efficient lookup at terminal time.

## 3) Are terminal counters capable of emitting fromState→finishedState and fromState→abandonedState anchored at terminal.ts?
- Yes. Configure transition counters with:
  - `toState = <finishedState>` and `fromMode = SUBSET` with `fromStates = [<entryState>]`
  - `toState = <abandonedState>` and `fromMode = SUBSET` with `fromStates = [<entryState>]`
- The evaluator posts using `eventTs` (terminal timestamp), so rollups are anchored to terminal time.
- `fromState` may be `null` for the first observed state of an object. This is stored as the internal initial-state marker in rollups.

Example config snippet (service config YAML shape):
```
transitionCounters:
  - name: entry_to_finished
    objectType: Order
    from: [FROM_STATE] # can include null for initial -> terminal
    to: FINISHED_STATE
  - name: entry_to_abandoned
    objectType: Order
    from: [FROM_STATE] # can include null for initial -> terminal
    to: ABANDONED_STATE
```

## 4) Does replacement reversal use stored footprints so fromState→abandonedState is reversible?
- Yes. Synthetic terminals store a footprint of from-states at injection time.
- Reversal uses `TransitionSyntheticSupersedeService.reverseSynthetic` with the recorded footprint.

## 5) Is postingId stable and deduped for idempotency?
- Yes. Posting IDs are generated from `eventId + metricKey + deltaSign + bucketStart`.
- Dedup is enforced by `transition_counter_postings` (see `JdbcTransitionCounterPostingIdRepository`).

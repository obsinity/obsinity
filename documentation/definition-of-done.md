# Definition of Done: State Transition Counters

## Configuration
- Provide a service config with state extractors, terminal states, and inference rules.
- Ensure inference idleFor and terminal states are validated by config materialization.
- Set `obsinity.stateTransitions.maxFromStates` and `obsinity.stateTransitions.maxSeenStates` when enabling ANY_SEEN.

## Inference Safety
- Enable inference only after verifying terminal states and snapshot ingestion.
- Verify synthetic injections occur at `lastEventTs + idleFor`.
- Confirm supersedes mark synthetic records `SUPERSEDED` and do not reopen objects.

## Funnel Validation
- Validate funnels using object-based EffectiveOutcome tables for a 1-day window.
- Check cohort counts match first-seen entry state totals.
- Confirm outcome origin breakdown (OBSERVED vs SYNTHETIC) sums to total outcomes.

## Metrics and Health
- Monitor synthetic injections, supersedes, fan-out truncations, and dedup hits.
- Check `/api/admin/state/health` for inference sweep times and synthetic counts.
- Investigate any non-zero seen-states cap exceed events.

## Out-of-Order Handling
- Backdated events are accepted; postings are aligned to event timestamps.
- Dedup uses posting IDs derived from eventId, metric key, and bucket start.

## Known Limitations
- High cardinality states require tuning maxSeenStates and maxFromStates.
- Inference sweep cadence controls latency of synthetic terminals.
- Outcome origin for synthetic events remains SYNTHETIC unless superseded.

## Tests
- Run `mvn -pl obsinity-service-core -am test` for regression coverage.

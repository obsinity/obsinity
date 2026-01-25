package com.obsinity.service.core.state.query;

import com.obsinity.service.core.counter.CounterBucket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransitionResolvedRollupQueryService {
    private final TransitionResolvedRollupQueryRepository repository;

    public TransitionResolvedRollupQueryService(TransitionResolvedRollupQueryRepository repository) {
        this.repository = repository;
    }

    public ResolvedCounts getResolvedCounts(
            UUID serviceId,
            String objectType,
            String attribute,
            String counterNameFinished,
            String counterNameAbandoned,
            String entryState,
            String finishedState,
            String abandonedState,
            CounterBucket bucket,
            Instant windowStart,
            Instant windowEnd) {
        long finished = repository.sumCounter(
                serviceId,
                objectType,
                attribute,
                counterNameFinished,
                entryState,
                finishedState,
                bucket,
                windowStart,
                windowEnd);
        long abandoned = repository.sumCounter(
                serviceId,
                objectType,
                attribute,
                counterNameAbandoned,
                entryState,
                abandonedState,
                bucket,
                windowStart,
                windowEnd);
        return new ResolvedCounts(finished, abandoned);
    }

    public ResolvedTransitionSummary getResolvedTransitionSummary(
            UUID serviceId,
            String objectType,
            String attribute,
            String counterName,
            List<String> fromStates,
            List<String> toStates,
            CounterBucket bucket,
            Instant windowStart,
            Instant windowEnd) {
        return getResolvedTransitionSummary(
                serviceId,
                objectType,
                attribute,
                counterName,
                fromStates,
                toStates,
                bucket,
                windowStart,
                windowEnd,
                false);
    }

    public ResolvedTransitionSummary getResolvedTransitionSummary(
            UUID serviceId,
            String objectType,
            String attribute,
            String counterName,
            List<String> fromStates,
            List<String> toStates,
            CounterBucket bucket,
            Instant windowStart,
            Instant windowEnd,
            boolean groupByFromState) {
        if (fromStates == null || fromStates.isEmpty() || toStates == null || toStates.isEmpty()) {
            return new ResolvedTransitionSummary(0L, List.of());
        }
        List<ResolvedTransitionCount> transitions = new java.util.ArrayList<>();
        long total = 0L;
        for (String fromState : fromStates) {
            for (String toState : toStates) {
                long count = repository.sumCounter(
                        serviceId,
                        objectType,
                        attribute,
                        counterName,
                        fromState,
                        toState,
                        bucket,
                        windowStart,
                        windowEnd);
                transitions.add(new ResolvedTransitionCount(fromState, toState, count, 0.0));
                total += count;
            }
        }
        return applyRatios(transitions, total, groupByFromState);
    }

    public ResolvedTransitionSummary getResolvedTransitionSummary(
            UUID serviceId,
            String objectType,
            String attribute,
            Map<String, String> counterNamesByToState,
            List<String> fromStates,
            List<String> toStates,
            CounterBucket bucket,
            Instant windowStart,
            Instant windowEnd,
            boolean groupByFromState) {
        if (counterNamesByToState == null || counterNamesByToState.isEmpty()) {
            return new ResolvedTransitionSummary(0L, List.of());
        }
        List<String> resolvedToStates = toStates == null || toStates.isEmpty()
                ? new java.util.ArrayList<>(counterNamesByToState.keySet())
                : toStates;
        if (fromStates == null || fromStates.isEmpty() || resolvedToStates.isEmpty()) {
            return new ResolvedTransitionSummary(0L, List.of());
        }
        List<ResolvedTransitionCount> transitions = new java.util.ArrayList<>();
        long total = 0L;
        for (String fromState : fromStates) {
            for (String toState : resolvedToStates) {
                String counterName = counterNamesByToState.get(toState);
                if (counterName == null) {
                    continue;
                }
                long count = repository.sumCounter(
                        serviceId,
                        objectType,
                        attribute,
                        counterName,
                        fromState,
                        toState,
                        bucket,
                        windowStart,
                        windowEnd);
                transitions.add(new ResolvedTransitionCount(fromState, toState, count, 0.0));
                total += count;
            }
        }
        return applyRatios(transitions, total, groupByFromState);
    }

    public double computeCompletionRate(ResolvedCounts counts) {
        if (counts == null) {
            return 0.0;
        }
        long denom = counts.startedFinished() + counts.startedAbandoned();
        if (denom == 0) {
            return 0.0;
        }
        return (double) counts.startedFinished() / (double) denom;
    }

    public record ResolvedCounts(long startedFinished, long startedAbandoned) {}

    public record ResolvedTransitionCount(String fromState, String toState, long count, double ratio) {}

    public record ResolvedTransitionSummary(long totalCount, List<ResolvedTransitionCount> transitions) {}

    private ResolvedTransitionSummary applyRatios(
            List<ResolvedTransitionCount> transitions, long total, boolean groupByFromState) {
        if (transitions.isEmpty()) {
            return new ResolvedTransitionSummary(0L, List.of());
        }
        List<ResolvedTransitionCount> adjusted = new java.util.ArrayList<>(transitions.size());
        Map<String, Long> groupTotals = null;
        if (groupByFromState) {
            groupTotals = new java.util.LinkedHashMap<>();
            for (ResolvedTransitionCount entry : transitions) {
                groupTotals.merge(entry.fromState(), entry.count(), Long::sum);
            }
        }
        for (ResolvedTransitionCount entry : transitions) {
            long denom = groupByFromState ? groupTotals.getOrDefault(entry.fromState(), 0L) : total;
            double ratio = denom > 0 ? (double) entry.count() / (double) denom : 0.0;
            adjusted.add(new ResolvedTransitionCount(entry.fromState(), entry.toState(), entry.count(), ratio));
        }
        return new ResolvedTransitionSummary(total, List.copyOf(adjusted));
    }
}

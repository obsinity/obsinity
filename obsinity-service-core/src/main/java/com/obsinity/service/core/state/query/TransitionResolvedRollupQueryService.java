package com.obsinity.service.core.state.query;

import com.obsinity.service.core.counter.CounterBucket;
import java.time.Instant;
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
}

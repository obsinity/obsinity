package com.obsinity.service.core.state.query;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransitionFunnelQueryService {
    private final TransitionFunnelQueryRepository repository;

    public TransitionFunnelQueryService(TransitionFunnelQueryRepository repository) {
        this.repository = repository;
    }

    public FunnelQueryResult conversionRate(
            UUID serviceId,
            String objectType,
            String attribute,
            String entryState,
            List<String> terminalStates,
            Instant windowStart,
            Instant windowEnd,
            Instant asOf) {
        long cohort = repository.cohortCount(serviceId, objectType, attribute, entryState, windowStart, windowEnd);
        List<TransitionFunnelQueryRepository.OutcomeRow> rows = repository.outcomeBreakdownAsOf(
                serviceId, objectType, attribute, entryState, windowStart, windowEnd, asOf, terminalStates);
        return summarize(cohort, rows);
    }

    public FunnelQueryResult conversionRateAsOf(
            UUID serviceId,
            String objectType,
            String attribute,
            String entryState,
            List<String> terminalStates,
            Instant windowStart,
            Instant windowEnd,
            Instant asOf) {
        return conversionRate(
                serviceId, objectType, attribute, entryState, terminalStates, windowStart, windowEnd, asOf);
    }

    public FunnelQueryResult conversionRateWithHorizon(
            UUID serviceId,
            String objectType,
            String attribute,
            String entryState,
            List<String> terminalStates,
            Instant windowStart,
            Instant windowEnd,
            Duration horizon) {
        long cohort = repository.cohortCount(serviceId, objectType, attribute, entryState, windowStart, windowEnd);
        List<TransitionFunnelQueryRepository.OutcomeRow> rows = repository.outcomeBreakdownWithHorizon(
                serviceId, objectType, attribute, entryState, windowStart, windowEnd, horizon, terminalStates);
        return summarize(cohort, rows);
    }

    private FunnelQueryResult summarize(long cohort, List<TransitionFunnelQueryRepository.OutcomeRow> rows) {
        Map<String, OutcomeCounts> outcomes = new LinkedHashMap<>();
        long terminalTotal = 0;
        for (TransitionFunnelQueryRepository.OutcomeRow row : rows) {
            OutcomeCounts counts = outcomes.computeIfAbsent(row.terminalState(), k -> new OutcomeCounts());
            counts.total += row.total();
            if ("SYNTHETIC".equalsIgnoreCase(row.origin())) {
                counts.synthetic += row.total();
            } else {
                counts.observed += row.total();
            }
            terminalTotal += row.total();
        }
        long open = Math.max(0, cohort - terminalTotal);
        return new FunnelQueryResult(cohort, open, outcomes);
    }

    public static final class OutcomeCounts {
        private long total;
        private long observed;
        private long synthetic;

        public long total() {
            return total;
        }

        public long observed() {
            return observed;
        }

        public long synthetic() {
            return synthetic;
        }

        public double rate(long cohort) {
            return cohort == 0 ? 0.0 : (double) total / (double) cohort;
        }
    }

    public record FunnelQueryResult(long cohortCount, long openCount, Map<String, OutcomeCounts> outcomes) {
        public double openRate() {
            return cohortCount == 0 ? 0.0 : (double) openCount / (double) cohortCount;
        }

        public OutcomeCounts outcome(String terminalState) {
            return outcomes.get(terminalState);
        }

        public long countFor(String terminalState) {
            OutcomeCounts counts = outcomes.get(terminalState);
            return counts == null ? 0 : counts.total();
        }

        public long observedFor(String terminalState) {
            OutcomeCounts counts = outcomes.get(terminalState);
            return counts == null ? 0 : counts.observed();
        }

        public long syntheticFor(String terminalState) {
            OutcomeCounts counts = outcomes.get(terminalState);
            return counts == null ? 0 : counts.synthetic();
        }
    }
}

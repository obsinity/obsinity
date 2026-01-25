package com.obsinity.service.core.state.transition.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.service.core.state.transition.counter.TerminalStateResolver;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class TransitionOutcomeServiceTest {

    @Test
    void entryTimestampIsImmutable() {
        UUID serviceId = UUID.randomUUID();
        InMemoryOutcomeRepository repo = new InMemoryOutcomeRepository();
        TransitionOutcomeService service = new TransitionOutcomeService(repo, terminalResolver());

        Instant first = Instant.parse("2025-01-01T00:00:00Z");
        Instant second = Instant.parse("2025-01-01T01:00:00Z");

        service.recordFirstSeen(serviceId, "Order", "o-1", "status", "STARTED", first);
        service.recordFirstSeen(serviceId, "Order", "o-1", "status", "STARTED", second);

        assertThat(repo.firstSeen.get(key(serviceId))).isEqualTo(first);
    }

    @Test
    void observedTerminalOverridesSynthetic() {
        UUID serviceId = UUID.randomUUID();
        InMemoryOutcomeRepository repo = new InMemoryOutcomeRepository();
        TransitionOutcomeService service = new TransitionOutcomeService(repo, terminalResolver());

        Instant syntheticTs = Instant.parse("2025-01-01T01:00:00Z");
        Instant observedTs = Instant.parse("2025-01-01T02:00:00Z");

        service.recordSyntheticTerminal(serviceId, "Order", "o-1", "status", "ABANDONED", syntheticTs, "syn-1");
        service.recordObservedTerminal(serviceId, "Order", "o-1", "status", "FINISHED", observedTs, "real-1");

        Outcome outcome = repo.outcomes.get(key(serviceId));
        assertThat(outcome.state).isEqualTo("FINISHED");
        assertThat(outcome.origin).isEqualTo("OBSERVED");
    }

    @Test
    void observedTerminalIsSticky() {
        UUID serviceId = UUID.randomUUID();
        InMemoryOutcomeRepository repo = new InMemoryOutcomeRepository();
        TransitionOutcomeService service = new TransitionOutcomeService(repo, terminalResolver());

        Instant observedTs = Instant.parse("2025-01-01T02:00:00Z");

        service.recordObservedTerminal(serviceId, "Order", "o-1", "status", "FINISHED", observedTs, "real-1");
        service.recordSyntheticTerminal(serviceId, "Order", "o-1", "status", "ABANDONED", observedTs, "syn-1");

        Outcome outcome = repo.outcomes.get(key(serviceId));
        assertThat(outcome.state).isEqualTo("FINISHED");
        assertThat(outcome.origin).isEqualTo("OBSERVED");
    }

    private TerminalStateResolver terminalResolver() {
        return (svcId, type) -> Set.of("FINISHED", "ABANDONED");
    }

    private String key(UUID serviceId) {
        return serviceId + "|Order|o-1|status";
    }

    private static final class InMemoryOutcomeRepository extends TransitionOutcomeRepository {
        private final Map<String, Instant> firstSeen = new ConcurrentHashMap<>();
        private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();

        private InMemoryOutcomeRepository() {
            super(null);
        }

        @Override
        public void recordFirstSeen(
                UUID serviceId,
                String objectType,
                String objectId,
                String attribute,
                String state,
                Instant firstSeenTs) {
            firstSeen.putIfAbsent(serviceId + "|" + objectType + "|" + objectId + "|" + attribute, firstSeenTs);
        }

        @Override
        public void recordSyntheticOutcome(
                UUID serviceId,
                String objectType,
                String objectId,
                String attribute,
                String terminalState,
                Instant terminalTs,
                String syntheticEventId) {
            String key = serviceId + "|" + objectType + "|" + objectId + "|" + attribute;
            outcomes.compute(key, (k, existing) -> {
                if (existing != null && "OBSERVED".equals(existing.origin)) {
                    return existing;
                }
                return new Outcome(terminalState, terminalTs, "SYNTHETIC");
            });
        }

        @Override
        public void recordObservedOutcome(
                UUID serviceId,
                String objectType,
                String objectId,
                String attribute,
                String terminalState,
                Instant terminalTs,
                String supersededByEventId) {
            String key = serviceId + "|" + objectType + "|" + objectId + "|" + attribute;
            outcomes.compute(key, (k, existing) -> {
                if (existing != null && "OBSERVED".equals(existing.origin)) {
                    return existing;
                }
                return new Outcome(terminalState, terminalTs, "OBSERVED");
            });
        }
    }

    private record Outcome(String state, Instant ts, String origin) {}
}

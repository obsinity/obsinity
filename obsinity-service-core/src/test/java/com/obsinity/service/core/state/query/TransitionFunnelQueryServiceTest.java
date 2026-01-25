package com.obsinity.service.core.state.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransitionFunnelQueryServiceTest {

    @Test
    void funnelCountsAndRatesMatchDataset() {
        UUID serviceId = UUID.randomUUID();
        Instant windowStart = Instant.parse("2025-01-01T00:00:00Z");
        Instant windowEnd = Instant.parse("2025-01-02T00:00:00Z");

        InMemoryFunnelRepository repo = new InMemoryFunnelRepository(serviceId);
        repo.addEntries(windowStart, 100);
        repo.addOutcomes("FINISHED", "OBSERVED", 60, windowStart.plus(Duration.ofHours(1)));
        repo.addOutcomes("ABANDONED", "OBSERVED", 10, windowStart.plus(Duration.ofHours(2)));
        repo.addOutcomes("ABANDONED", "SYNTHETIC", 30, windowStart.plus(Duration.ofHours(3)));

        TransitionFunnelQueryService service = new TransitionFunnelQueryService(repo);
        TransitionFunnelQueryService.FunnelQueryResult result = service.conversionRate(
                serviceId,
                "Order",
                "status",
                "STARTED",
                List.of("FINISHED", "ABANDONED"),
                windowStart,
                windowEnd,
                windowEnd);

        assertThat(result.cohortCount()).isEqualTo(100);
        assertThat(result.openCount()).isEqualTo(0);
        assertThat(result.outcome("FINISHED").total()).isEqualTo(60);
        assertThat(result.outcome("ABANDONED").total()).isEqualTo(40);
        assertThat(result.outcome("ABANDONED").observed()).isEqualTo(10);
        assertThat(result.outcome("ABANDONED").synthetic()).isEqualTo(30);
        assertThat(result.openRate()).isEqualTo(0.0);
        assertThat(result.countFor("FINISHED")).isEqualTo(60);
        assertThat(result.countFor("ABANDONED")).isEqualTo(40);
    }

    @Test
    void asOfVsHorizonDifferForLateTerminals() {
        UUID serviceId = UUID.randomUUID();
        Instant windowStart = Instant.parse("2025-01-01T00:00:00Z");
        Instant windowEnd = Instant.parse("2025-01-01T01:00:00Z");
        Instant asOf = Instant.parse("2025-01-01T01:30:00Z");

        InMemoryFunnelRepository repo = new InMemoryFunnelRepository(serviceId);
        repo.addEntry(windowStart.plus(Duration.ofMinutes(50)), "o-1");
        repo.addEntry(windowStart.plus(Duration.ofMinutes(55)), "o-2");
        repo.addOutcome("o-1", "FINISHED", "OBSERVED", windowStart.plus(Duration.ofMinutes(55)));
        repo.addOutcome("o-2", "FINISHED", "OBSERVED", windowStart.plus(Duration.ofHours(3)));

        TransitionFunnelQueryService service = new TransitionFunnelQueryService(repo);
        TransitionFunnelQueryService.FunnelQueryResult asOfResult = service.conversionRate(
                serviceId, "Order", "status", "STARTED", List.of("FINISHED"), windowStart, windowEnd, asOf);

        TransitionFunnelQueryService.FunnelQueryResult horizonResult = service.conversionRateWithHorizon(
                serviceId,
                "Order",
                "status",
                "STARTED",
                List.of("FINISHED"),
                windowStart,
                windowEnd,
                Duration.ofHours(4));

        assertThat(asOfResult.cohortCount()).isEqualTo(2);
        assertThat(asOfResult.openCount()).isEqualTo(1);
        assertThat(horizonResult.openCount()).isEqualTo(0);
    }

    @Test
    void replacementShiftsOutcomeToObserved() {
        UUID serviceId = UUID.randomUUID();
        Instant windowStart = Instant.parse("2025-01-01T00:00:00Z");
        Instant windowEnd = Instant.parse("2025-01-02T00:00:00Z");

        InMemoryFunnelRepository repo = new InMemoryFunnelRepository(serviceId);
        repo.addEntry(windowStart.plus(Duration.ofMinutes(10)), "o-1");
        repo.addOutcome("o-1", "ABANDONED", "SYNTHETIC", windowStart.plus(Duration.ofHours(2)));
        repo.replaceOutcome("o-1", "FINISHED", "OBSERVED", windowStart.plus(Duration.ofHours(3)));

        TransitionFunnelQueryService service = new TransitionFunnelQueryService(repo);
        TransitionFunnelQueryService.FunnelQueryResult result = service.conversionRate(
                serviceId,
                "Order",
                "status",
                "STARTED",
                List.of("FINISHED", "ABANDONED"),
                windowStart,
                windowEnd,
                windowEnd);

        assertThat(result.outcome("FINISHED").observed()).isEqualTo(1);
        assertThat(result.outcome("ABANDONED")).isNull();
    }

    @Test
    void objectBasedFunnelIgnoresDuplicateTransitions() {
        UUID serviceId = UUID.randomUUID();
        Instant windowStart = Instant.parse("2025-01-01T00:00:00Z");
        Instant windowEnd = Instant.parse("2025-01-02T00:00:00Z");

        InMemoryFunnelRepository repo = new InMemoryFunnelRepository(serviceId);
        repo.addEntry(windowStart.plus(Duration.ofMinutes(1)), "o-1");
        repo.addOutcome("o-1", "FINISHED", "OBSERVED", windowStart.plus(Duration.ofHours(2)));
        repo.addOutcome("o-1", "FINISHED", "OBSERVED", windowStart.plus(Duration.ofHours(3)));

        TransitionFunnelQueryService service = new TransitionFunnelQueryService(repo);
        TransitionFunnelQueryService.FunnelQueryResult result = service.conversionRate(
                serviceId, "Order", "status", "STARTED", List.of("FINISHED"), windowStart, windowEnd, windowEnd);

        assertThat(result.cohortCount()).isEqualTo(1);
        assertThat(result.countFor("FINISHED")).isEqualTo(1);
    }

    @Test
    void entryTimestampIsFirstOccurrenceOnly() {
        UUID serviceId = UUID.randomUUID();
        Instant windowStart = Instant.parse("2025-01-01T00:00:00Z");
        Instant windowEnd = Instant.parse("2025-01-02T00:00:00Z");

        InMemoryFunnelRepository repo = new InMemoryFunnelRepository(serviceId);
        repo.addEntry(windowStart.plus(Duration.ofMinutes(10)), "o-1");
        repo.addEntry(windowStart.plus(Duration.ofHours(2)), "o-1");
        repo.addOutcome("o-1", "FINISHED", "OBSERVED", windowStart.plus(Duration.ofHours(3)));

        TransitionFunnelQueryService service = new TransitionFunnelQueryService(repo);
        TransitionFunnelQueryService.FunnelQueryResult result = service.conversionRate(
                serviceId, "Order", "status", "STARTED", List.of("FINISHED"), windowStart, windowEnd, windowEnd);

        assertThat(result.cohortCount()).isEqualTo(1);
    }

    private static final class InMemoryFunnelRepository extends TransitionFunnelQueryRepository {
        private final UUID serviceId;
        private final Map<String, Entry> entries = new HashMap<>();
        private final Map<String, Outcome> outcomes = new HashMap<>();

        private InMemoryFunnelRepository(UUID serviceId) {
            super(null);
            this.serviceId = serviceId;
        }

        void addEntries(Instant base, int count) {
            for (int i = 0; i < count; i++) {
                addEntry(base.plusSeconds(i), "o-" + i);
            }
        }

        void addEntry(Instant entryTs, String objectId) {
            entries.putIfAbsent(objectId, new Entry(entryTs));
        }

        void addOutcomes(String state, String origin, int count, Instant terminalTs) {
            int start = outcomes.size();
            for (int i = 0; i < count; i++) {
                String objectId = "o-" + (start + i);
                if (!entries.containsKey(objectId)) {
                    addEntry(terminalTs.minus(Duration.ofMinutes(5)), objectId);
                }
                addOutcome(objectId, state, origin, terminalTs);
            }
        }

        void addOutcome(String objectId, String state, String origin, Instant terminalTs) {
            outcomes.put(objectId, new Outcome(state, origin, terminalTs));
        }

        void replaceOutcome(String objectId, String state, String origin, Instant terminalTs) {
            outcomes.put(objectId, new Outcome(state, origin, terminalTs));
        }

        @Override
        public long cohortCount(
                UUID serviceId,
                String objectType,
                String attribute,
                String entryState,
                Instant windowStart,
                Instant windowEnd) {
            return entries.values().stream()
                    .filter(entry -> !entry.entryTs.isBefore(windowStart) && entry.entryTs.isBefore(windowEnd))
                    .count();
        }

        @Override
        public List<OutcomeRow> outcomeBreakdownAsOf(
                UUID serviceId,
                String objectType,
                String attribute,
                String entryState,
                Instant windowStart,
                Instant windowEnd,
                Instant asOf,
                List<String> terminalStates) {
            return breakdown(
                    windowStart,
                    windowEnd,
                    terminalStates,
                    (entry, outcome) -> outcome.terminalTs.isBefore(asOf) || outcome.terminalTs.equals(asOf));
        }

        @Override
        public List<OutcomeRow> outcomeBreakdownWithHorizon(
                UUID serviceId,
                String objectType,
                String attribute,
                String entryState,
                Instant windowStart,
                Instant windowEnd,
                Duration horizon,
                List<String> terminalStates) {
            return breakdown(
                    windowStart,
                    windowEnd,
                    terminalStates,
                    (entry, outcome) -> !outcome.terminalTs.isAfter(entry.entryTs.plus(horizon)));
        }

        private List<OutcomeRow> breakdown(
                Instant windowStart, Instant windowEnd, List<String> terminalStates, OutcomePredicate predicate) {
            Map<String, Long> totals = new HashMap<>();
            List<OutcomeRow> rows = new ArrayList<>();
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                if (entry.getValue().entryTs.isBefore(windowStart)
                        || !entry.getValue().entryTs.isBefore(windowEnd)) {
                    continue;
                }
                Outcome outcome = outcomes.get(entry.getKey());
                if (outcome == null || !terminalStates.contains(outcome.state)) {
                    continue;
                }
                if (!predicate.matches(entry.getValue(), outcome)) {
                    continue;
                }
                String key = outcome.state + "|" + outcome.origin;
                totals.merge(key, 1L, Long::sum);
            }
            for (Map.Entry<String, Long> entry : totals.entrySet()) {
                String[] parts = entry.getKey().split("\\|", -1);
                rows.add(new OutcomeRow(parts[0], parts[1], entry.getValue()));
            }
            return rows;
        }

        private record Entry(Instant entryTs) {}

        private record Outcome(String state, String origin, Instant terminalTs) {}

        private interface OutcomePredicate {
            boolean matches(Entry entry, Outcome outcome);
        }
    }
}

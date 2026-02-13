package com.obsinity.reference.demodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DbDrivenProfileGeneratorTest {

    @Test
    void belowCapCreatesCreatePerRunAndNeverExceedsTarget() {
        FakeRepo repo = new FakeRepo();
        DemoProfileGeneratorProperties props = baseProps();
        props.setEnabled(true);
        props.setTargetCount(5);
        props.setCreatePerRun(3);

        DbDrivenProfileGenerator generator = buildGenerator(repo, props, Instant.parse("2026-02-12T12:00:00Z"));
        generator.runOnce();
        assertEquals(3, repo.countProfiles());

        generator.runOnce();
        assertEquals(5, repo.countProfiles());

        generator.runOnce();
        assertEquals(5, repo.countProfiles());
    }

    @Test
    void ageGatingRespectsMinAndMaxAge() {
        FakeRepo repo = new FakeRepo();
        DemoProfileGeneratorProperties props = baseProps();
        props.setEnabled(true);
        props.setCreatePerRun(0);

        Instant now = Instant.parse("2026-02-12T12:00:00Z");
        repo.add("NONE", now.minusSeconds(2));
        repo.add("NONE", now.minusSeconds(20));
        repo.add("NONE", now.minusSeconds(1200));

        DemoProfileGeneratorProperties.TransitionRule rule = new DemoProfileGeneratorProperties.TransitionRule();
        DemoProfileGeneratorProperties.Selection select = new DemoProfileGeneratorProperties.Selection();
        select.setMinAge(Duration.ofSeconds(10));
        select.setMaxAge(Duration.ofMinutes(10));
        select.setLimitPerRun(10);
        rule.setSelect(select);
        rule.setNext(List.of("ACTIVE"));

        props.setTransitions(Map.of("NONE", rule));

        DbDrivenProfileGenerator generator = buildGenerator(repo, props, now);
        generator.runOnce();

        assertEquals(1, repo.countByState("ACTIVE"));
        assertEquals(2, repo.countByState("NONE"));
    }

    @Test
    void limitPerRunIsRespectedPerState() {
        FakeRepo repo = new FakeRepo();
        DemoProfileGeneratorProperties props = baseProps();
        props.setEnabled(true);
        props.setCreatePerRun(0);

        Instant now = Instant.parse("2026-02-12T12:00:00Z");
        for (int i = 0; i < 20; i++) {
            repo.add("ACTIVE", now.minusSeconds(100 + i));
        }

        DemoProfileGeneratorProperties.TransitionRule rule = new DemoProfileGeneratorProperties.TransitionRule();
        DemoProfileGeneratorProperties.Selection select = new DemoProfileGeneratorProperties.Selection();
        select.setMinAge(Duration.ofSeconds(1));
        select.setLimitPerRun(5);
        rule.setSelect(select);
        rule.setNext(List.of("INACTIVE"));
        props.setTransitions(Map.of("ACTIVE", rule));

        DbDrivenProfileGenerator generator = buildGenerator(repo, props, now);
        generator.runOnce();

        assertEquals(5, repo.countByState("INACTIVE"));
        assertEquals(15, repo.countByState("ACTIVE"));
    }

    @Test
    void nextStateAlwaysFromAllowedSet() {
        FakeRepo repo = new FakeRepo();
        DemoProfileGeneratorProperties props = baseProps();
        props.setEnabled(true);
        props.setCreatePerRun(0);
        props.setTransitionSeed(12345L);

        Instant now = Instant.parse("2026-02-12T12:00:00Z");
        for (int i = 0; i < 50; i++) {
            repo.add("NONE", now.minusSeconds(100 + i));
        }

        DemoProfileGeneratorProperties.TransitionRule rule = new DemoProfileGeneratorProperties.TransitionRule();
        DemoProfileGeneratorProperties.Selection select = new DemoProfileGeneratorProperties.Selection();
        select.setMinAge(Duration.ZERO);
        select.setLimitPerRun(50);
        rule.setSelect(select);
        rule.setNext(List.of("ACTIVE", "PENDING_EMAIL", "PENDING_KYC"));
        props.setTransitions(Map.of("NONE", rule));

        DbDrivenProfileGenerator generator = buildGenerator(repo, props, now);
        generator.runOnce();

        Set<String> allowed = Set.of("ACTIVE", "PENDING_EMAIL", "PENDING_KYC");
        for (FakeRepo.Row row : repo.rows.values()) {
            if (!"NONE".equals(row.state)) {
                assertTrue(allowed.contains(row.state));
            }
        }
    }

    @Test
    void deterministicSeedProducesReproducibleResult() {
        DemoProfileGeneratorProperties props1 = deterministicProps();
        DemoProfileGeneratorProperties props2 = deterministicProps();

        Instant now = Instant.parse("2026-02-12T12:00:00Z");
        FakeRepo repo1 = seededRepo(now);
        FakeRepo repo2 = seededRepo(now);

        buildGenerator(repo1, props1, now).runOnce();
        buildGenerator(repo2, props2, now).runOnce();

        Map<UUID, String> states1 = repo1.snapshotStates();
        Map<UUID, String> states2 = repo2.snapshotStates();
        assertEquals(states1, states2);
    }

    @Test
    void casConflictIsSkippedWithoutFailure() {
        FakeRepo repo = new FakeRepo();
        DemoProfileGeneratorProperties props = baseProps();
        props.setEnabled(true);
        props.setCreatePerRun(0);

        Instant now = Instant.parse("2026-02-12T12:00:00Z");
        UUID conflictId = repo.add("ACTIVE", now.minusSeconds(120));
        repo.setCasConflictId(conflictId);

        DemoProfileGeneratorProperties.TransitionRule rule = new DemoProfileGeneratorProperties.TransitionRule();
        DemoProfileGeneratorProperties.Selection select = new DemoProfileGeneratorProperties.Selection();
        select.setMinAge(Duration.ofSeconds(1));
        select.setLimitPerRun(1);
        rule.setSelect(select);
        rule.setNext(List.of("INACTIVE"));
        props.setTransitions(Map.of("ACTIVE", rule));

        DbDrivenProfileGenerator generator = buildGenerator(repo, props, now);
        generator.runOnce();

        assertEquals("RACE_UPDATED", repo.rows.get(conflictId).state);
    }

    private static DemoProfileGeneratorProperties baseProps() {
        DemoProfileGeneratorProperties props = new DemoProfileGeneratorProperties();
        props.setRunEvery(Duration.ofSeconds(1));
        props.setOversampleFactor(5);
        props.setMaxSelectionPerState(1000);
        props.setInitialState("NEW");
        return props;
    }

    private static DemoProfileGeneratorProperties deterministicProps() {
        DemoProfileGeneratorProperties props = baseProps();
        props.setEnabled(true);
        props.setCreatePerRun(0);
        props.setTransitionSeed(987654321L);

        DemoProfileGeneratorProperties.TransitionRule rule = new DemoProfileGeneratorProperties.TransitionRule();
        DemoProfileGeneratorProperties.Selection select = new DemoProfileGeneratorProperties.Selection();
        select.setMinAge(Duration.ZERO);
        select.setLimitPerRun(5);
        rule.setSelect(select);
        rule.setNext(List.of("INACTIVE", "SUSPENDED"));
        props.setTransitions(Map.of("ACTIVE", rule));
        return props;
    }

    private static FakeRepo seededRepo(Instant now) {
        FakeRepo repo = new FakeRepo();
        for (int i = 0; i < 10; i++) {
            UUID id = UUID.nameUUIDFromBytes(("seeded-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            repo.addWithId(id, "ACTIVE", now.minusSeconds(200 + i));
        }
        return repo;
    }

    private static DbDrivenProfileGenerator buildGenerator(
            FakeRepo repo, DemoProfileGeneratorProperties props, Instant now) {
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        FakeIngestService ingestService = new FakeIngestService();
        RandomProvider provider = (instant, runEverySeconds) -> {
            Long seed = props.getTransitionSeed();
            if (seed == null) {
                return new Random(42L);
            }
            long bucket = instant.getEpochSecond() / Math.max(1L, runEverySeconds);
            return new Random(seed ^ bucket);
        };
        return new DbDrivenProfileGenerator(props, repo, provider, ingestService, fixedClock);
    }

    static final class FakeRepo implements ProfileGeneratorRepository {

        private final Map<UUID, Row> rows = new LinkedHashMap<>();
        private UUID casConflictId;

        UUID add(String state, Instant stateChangedAt) {
            UUID id = UUID.randomUUID();
            rows.put(id, new Row(id, state, stateChangedAt));
            return id;
        }

        void addWithId(UUID id, String state, Instant stateChangedAt) {
            rows.put(id, new Row(id, state, stateChangedAt));
        }

        void setCasConflictId(UUID casConflictId) {
            this.casConflictId = casConflictId;
        }

        int countByState(String state) {
            int count = 0;
            for (Row row : rows.values()) {
                if (state.equals(row.state)) {
                    count++;
                }
            }
            return count;
        }

        Map<UUID, String> snapshotStates() {
            Map<UUID, String> snapshot = new HashMap<>();
            rows.forEach((id, row) -> snapshot.put(id, row.state));
            return snapshot;
        }

        @Override
        public long countProfiles() {
            return rows.size();
        }

        @Override
        public int insertProfiles(List<UUID> ids, String initialState, Instant now) {
            int inserted = 0;
            for (UUID id : ids) {
                if (!rows.containsKey(id)) {
                    rows.put(id, new Row(id, initialState, now));
                    inserted++;
                }
            }
            return inserted;
        }

        @Override
        public List<ProfileCandidate> selectCandidates(
                String fromState, Instant upperBound, Instant lowerBoundInclusive, int limit) {
            List<ProfileCandidate> out = new ArrayList<>();
            for (Row row : rows.values()) {
                if (!fromState.equals(row.state)) {
                    continue;
                }
                if (row.stateChangedAt.isAfter(upperBound)) {
                    continue;
                }
                if (lowerBoundInclusive != null && row.stateChangedAt.isBefore(lowerBoundInclusive)) {
                    continue;
                }
                out.add(new ProfileCandidate(row.id, row.stateChangedAt));
            }
            out.sort((a, b) -> a.stateChangedAt().compareTo(b.stateChangedAt()));
            return out.size() <= limit ? out : out.subList(0, limit);
        }

        @Override
        public int casUpdateState(
                UUID id, String fromState, Instant expectedStateChangedAt, String toState, Instant now) {
            Row current = rows.get(id);
            if (current == null) {
                return 0;
            }
            if (id.equals(casConflictId)) {
                current.state = "RACE_UPDATED";
                current.stateChangedAt = now.minusSeconds(1);
                casConflictId = null;
            }
            if (!fromState.equals(current.state) || !expectedStateChangedAt.equals(current.stateChangedAt)) {
                return 0;
            }
            current.state = toState;
            current.stateChangedAt = now;
            return 1;
        }

        static final class Row {
            final UUID id;
            volatile String state;
            volatile Instant stateChangedAt;

            Row(UUID id, String state, Instant stateChangedAt) {
                this.id = id;
                this.state = state;
                this.stateChangedAt = stateChangedAt;
            }
        }
    }

    static final class FakeIngestService implements EventIngestService {

        @Override
        public int ingestOne(EventEnvelope e) {
            return e == null ? 0 : 1;
        }

        @Override
        public int ingestBatch(List<EventEnvelope> input) {
            return input == null ? 0 : input.size();
        }
    }
}

package com.obsinity.service.core.state.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.FromMode;
import com.obsinity.service.core.config.RegistrySnapshot;
import com.obsinity.service.core.config.ServiceConfig;
import com.obsinity.service.core.config.TransitionCounterDefinition;
import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.state.transition.codec.StateCodec;
import com.obsinity.service.core.state.transition.counter.SeenStates;
import com.obsinity.service.core.state.transition.counter.TerminalStateResolver;
import com.obsinity.service.core.state.transition.counter.TransitionCounterEvaluator;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPosting;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingIdFactory;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingIdRepository;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingService;
import com.obsinity.service.core.state.transition.counter.TransitionCounterRollupRepository;
import com.obsinity.service.core.state.transition.counter.TransitionCounterSnapshot;
import com.obsinity.service.core.state.transition.counter.TransitionCounterSnapshotStore;
import com.obsinity.service.core.state.transition.inference.SyntheticTerminalRecord;
import com.obsinity.service.core.state.transition.inference.SyntheticTerminalRecordRepository;
import com.obsinity.service.core.state.transition.inference.TransitionSyntheticSupersedeService;
import com.obsinity.service.core.state.transition.outcome.TransitionOutcomeRepository;
import com.obsinity.service.core.state.transition.outcome.TransitionOutcomeService;
import com.obsinity.service.core.state.transition.telemetry.TransitionTelemetry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TransitionResolvedRollupQueryServiceTest {
    private static final String COUNTER_FINISHED = "started_to_finished";
    private static final String COUNTER_ABANDONED = "started_to_abandoned";

    @Test
    void startedThenFinishedCounts() {
        TestHarness harness = new TestHarness();
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2025-01-01T01:00:00Z");

        harness.evaluator.evaluate(harness.serviceId, "evt-1", t0, "Order", "o-1", "status", "STARTED");
        harness.evaluator.evaluate(harness.serviceId, "evt-2", t1, "Order", "o-1", "status", "FINISHED");

        TransitionResolvedRollupQueryService.ResolvedCounts counts = harness.queryService.getResolvedCounts(
                harness.serviceId,
                "Order",
                "status",
                COUNTER_FINISHED,
                COUNTER_ABANDONED,
                "STARTED",
                "FINISHED",
                "ABANDONED",
                CounterBucket.S5,
                t1,
                t1.plusSeconds(5));

        assertThat(counts.startedFinished()).isEqualTo(1);
        assertThat(counts.startedAbandoned()).isEqualTo(0);
        assertThat(harness.queryService.computeCompletionRate(counts)).isEqualTo(1.0);
    }

    @Test
    void startedThenAbandonedCounts() {
        TestHarness harness = new TestHarness();
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2025-01-01T01:00:00Z");

        harness.evaluator.evaluate(harness.serviceId, "evt-1", t0, "Order", "o-1", "status", "STARTED");
        harness.evaluator.evaluate(harness.serviceId, "evt-2", t1, "Order", "o-1", "status", "ABANDONED");

        TransitionResolvedRollupQueryService.ResolvedCounts counts = harness.queryService.getResolvedCounts(
                harness.serviceId,
                "Order",
                "status",
                COUNTER_FINISHED,
                COUNTER_ABANDONED,
                "STARTED",
                "FINISHED",
                "ABANDONED",
                CounterBucket.S5,
                t1,
                t1.plusSeconds(5));

        assertThat(counts.startedFinished()).isEqualTo(0);
        assertThat(counts.startedAbandoned()).isEqualTo(1);
        assertThat(harness.queryService.computeCompletionRate(counts)).isEqualTo(0.0);
    }

    @Test
    void mixedFinishedAndAbandonedCounts() {
        TestHarness harness = new TestHarness();
        Instant base = Instant.parse("2025-01-01T00:00:00Z");

        for (int i = 0; i < 3; i++) {
            harness.evaluator.evaluate(harness.serviceId, "start-" + i, base, "Order", "o-" + i, "status", "STARTED");
            harness.evaluator.evaluate(
                    harness.serviceId,
                    "finish-" + i,
                    base.plusSeconds(10 + i),
                    "Order",
                    "o-" + i,
                    "status",
                    "FINISHED");
        }
        for (int i = 3; i < 5; i++) {
            harness.evaluator.evaluate(harness.serviceId, "start-" + i, base, "Order", "o-" + i, "status", "STARTED");
            harness.evaluator.evaluate(
                    harness.serviceId,
                    "abandon-" + i,
                    base.plusSeconds(20 + i),
                    "Order",
                    "o-" + i,
                    "status",
                    "ABANDONED");
        }

        TransitionResolvedRollupQueryService.ResolvedCounts counts = harness.queryService.getResolvedCounts(
                harness.serviceId,
                "Order",
                "status",
                COUNTER_FINISHED,
                COUNTER_ABANDONED,
                "STARTED",
                "FINISHED",
                "ABANDONED",
                CounterBucket.S5,
                base,
                base.plusSeconds(60));

        assertThat(counts.startedFinished()).isEqualTo(3);
        assertThat(counts.startedAbandoned()).isEqualTo(2);
        assertThat(harness.queryService.computeCompletionRate(counts)).isEqualTo(0.6);
    }

    @Test
    void finishedWithoutStartedDoesNotCount() {
        TestHarness harness = new TestHarness();
        Instant t1 = Instant.parse("2025-01-01T01:00:00Z");

        harness.evaluator.evaluate(harness.serviceId, "evt-1", t1, "Order", "o-1", "status", "FINISHED");

        TransitionResolvedRollupQueryService.ResolvedCounts counts = harness.queryService.getResolvedCounts(
                harness.serviceId,
                "Order",
                "status",
                COUNTER_FINISHED,
                COUNTER_ABANDONED,
                "STARTED",
                "FINISHED",
                "ABANDONED",
                CounterBucket.S5,
                t1,
                t1.plusSeconds(5));

        assertThat(counts.startedFinished()).isEqualTo(0);
        assertThat(counts.startedAbandoned()).isEqualTo(0);
    }

    @Test
    void replacementAcrossBucketsReversesAndApplies() {
        TestHarness harness = new TestHarness();
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2025-01-01T01:00:00Z");
        Instant t2 = Instant.parse("2025-01-01T02:00:00Z");

        harness.evaluator.evaluate(harness.serviceId, "evt-1", t0, "Order", "o-1", "status", "STARTED");
        String syntheticEventId = "syn-1";
        SyntheticTerminalRecord record = harness.recordSyntheticTerminal("o-1", "status", syntheticEventId, t1);
        TransitionCounterEvaluator.SyntheticContext context =
                new TransitionCounterEvaluator.SyntheticContext(syntheticEventId, harness.recordRepository);
        harness.evaluator.evaluate(
                harness.serviceId, syntheticEventId, t1, "Order", "o-1", "status", "ABANDONED", context);

        harness.supersedeService.handleIfSuperseding(
                harness.serviceId, harness.realEvent("real-1", t2), "Order", "o-1", "status", "FINISHED");

        TransitionResolvedRollupQueryService.ResolvedCounts t1Counts = harness.queryService.getResolvedCounts(
                harness.serviceId,
                "Order",
                "status",
                COUNTER_FINISHED,
                COUNTER_ABANDONED,
                "STARTED",
                "FINISHED",
                "ABANDONED",
                CounterBucket.S5,
                t1,
                t1.plusSeconds(5));
        TransitionResolvedRollupQueryService.ResolvedCounts t2Counts = harness.queryService.getResolvedCounts(
                harness.serviceId,
                "Order",
                "status",
                COUNTER_FINISHED,
                COUNTER_ABANDONED,
                "STARTED",
                "FINISHED",
                "ABANDONED",
                CounterBucket.S5,
                t2,
                t2.plusSeconds(5));

        assertThat(t1Counts.startedFinished()).isEqualTo(0);
        assertThat(t1Counts.startedAbandoned()).isEqualTo(0);
        assertThat(harness.queryService.computeCompletionRate(t1Counts)).isEqualTo(0.0);
        assertThat(t2Counts.startedFinished()).isEqualTo(1);
        assertThat(harness.queryService.computeCompletionRate(t2Counts)).isEqualTo(1.0);
    }

    @Test
    void duplicateTerminalEventIsIdempotent() {
        TestHarness harness = new TestHarness();
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2025-01-01T01:00:00Z");

        harness.evaluator.evaluate(harness.serviceId, "evt-1", t0, "Order", "o-1", "status", "STARTED");
        harness.evaluator.evaluate(harness.serviceId, "evt-2", t1, "Order", "o-1", "status", "FINISHED");
        harness.evaluator.evaluate(harness.serviceId, "evt-2", t1, "Order", "o-1", "status", "FINISHED");

        TransitionResolvedRollupQueryService.ResolvedCounts counts = harness.queryService.getResolvedCounts(
                harness.serviceId,
                "Order",
                "status",
                COUNTER_FINISHED,
                COUNTER_ABANDONED,
                "STARTED",
                "FINISHED",
                "ABANDONED",
                CounterBucket.S5,
                t1,
                t1.plusSeconds(5));

        assertThat(counts.startedFinished()).isEqualTo(1);
    }

    private static final class TestHarness {
        private final UUID serviceId = UUID.randomUUID();
        private final TestCodec codec = new TestCodec();
        private final InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
        private final InMemoryPostingIdRepository postingIds = new InMemoryPostingIdRepository();
        private final InMemoryRollupRepository rollups = new InMemoryRollupRepository();
        private final TransitionCounterPostingService postingService =
                new TransitionCounterPostingService(postingIds, rollups, noopTransactionTemplate());
        private final TransitionCounterEvaluator evaluator;
        private final InMemorySyntheticRecordRepository recordRepository = new InMemorySyntheticRecordRepository();
        private final TransitionSyntheticSupersedeService supersedeService;
        private final TransitionResolvedRollupQueryService queryService;

        private TestHarness() {
            List<TransitionCounterDefinition> rules = List.of(
                    new TransitionCounterDefinition(
                            COUNTER_FINISHED, "Order", "FINISHED", FromMode.SUBSET, List.of("STARTED"), false),
                    new TransitionCounterDefinition(
                            COUNTER_ABANDONED, "Order", "ABANDONED", FromMode.SUBSET, List.of("STARTED"), false));
            ServiceConfig svc =
                    new ServiceConfig(serviceId, "svc", Instant.now(), Map.of(), List.of(), rules, List.of());
            RegistrySnapshot snapshot = new RegistrySnapshot(Map.of(serviceId, svc), Instant.now());
            com.obsinity.service.core.config.ConfigRegistry registry =
                    new com.obsinity.service.core.config.ConfigRegistry();
            registry.swap(snapshot);
            ConfigLookup lookup = new ConfigLookup(registry);
            evaluator = new TransitionCounterEvaluator(
                    lookup,
                    snapshotStore,
                    postingService,
                    terminalResolver(),
                    new TransitionCounterPostingIdFactory(),
                    new NoopTelemetry(),
                    codec);
            TransitionOutcomeRepository outcomeRepository = new InMemoryOutcomeRepository();
            TransitionOutcomeService outcomeService =
                    new TransitionOutcomeService(outcomeRepository, terminalResolver());
            supersedeService = new TransitionSyntheticSupersedeService(
                    recordRepository,
                    evaluator,
                    postingService,
                    new TransitionCounterPostingIdFactory(),
                    terminalResolver(),
                    outcomeService,
                    new NoopTelemetry(),
                    java.time.Clock.systemUTC());
            queryService = new TransitionResolvedRollupQueryService(new InMemoryQueryRepository(rollups));
        }

        private TerminalStateResolver terminalResolver() {
            return (svcId, type) -> Set.of("FINISHED", "ABANDONED");
        }

        private SyntheticTerminalRecord recordSyntheticTerminal(
                String objectId, String attribute, String syntheticEventId, Instant ts) {
            SyntheticTerminalRecord record = new SyntheticTerminalRecord(
                    serviceId,
                    "Order",
                    objectId,
                    attribute,
                    "rule-1",
                    syntheticEventId,
                    ts,
                    "ABANDONED",
                    "obsinity",
                    "TIMEOUT",
                    "SYNTHETIC",
                    "ACTIVE",
                    ts.minusSeconds(10),
                    "STARTED",
                    null,
                    null,
                    null,
                    List.of(new com.obsinity.service.core.state.transition.counter.TransitionCounterFootprintEntry(
                            COUNTER_ABANDONED, List.of("STARTED"), "ABANDONED")));
            recordRepository.insertIfEligible(record, record.lastEventTs());
            return record;
        }

        private com.obsinity.service.core.model.EventEnvelope realEvent(String eventId, Instant ts) {
            return com.obsinity.service.core.model.EventEnvelope.builder()
                    .serviceId("svc-a")
                    .eventType("order.event")
                    .eventId(eventId)
                    .timestamp(ts)
                    .ingestedAt(ts)
                    .attributes(Map.of("status", "FINISHED"))
                    .build();
        }
    }

    private static final class InMemoryQueryRepository extends TransitionResolvedRollupQueryRepository {
        private final InMemoryRollupRepository rollups;

        private InMemoryQueryRepository(InMemoryRollupRepository rollups) {
            super(null);
            this.rollups = rollups;
        }

        @Override
        public long sumCounter(
                UUID serviceId,
                String objectType,
                String attribute,
                String counterName,
                String fromState,
                String toState,
                CounterBucket bucket,
                Instant windowStart,
                Instant windowEnd) {
            return rollups.sum(
                    bucket, serviceId, objectType, attribute, counterName, fromState, toState, windowStart, windowEnd);
        }
    }

    private static final class InMemorySnapshotStore implements TransitionCounterSnapshotStore {
        private final Map<String, TransitionCounterSnapshot> snapshots = new LinkedHashMap<>();

        @Override
        public TransitionCounterSnapshot find(UUID serviceId, String objectType, String objectId, String attribute) {
            return snapshots.get(key(serviceId, objectType, objectId, attribute));
        }

        @Override
        public void upsert(
                UUID serviceId,
                String objectType,
                String objectId,
                String attribute,
                String lastState,
                SeenStates seenStates,
                Instant lastEventTs,
                String terminalState) {
            snapshots.put(
                    key(serviceId, objectType, objectId, attribute),
                    new TransitionCounterSnapshot(lastState, seenStates, lastEventTs, terminalState));
        }

        private String key(UUID serviceId, String objectType, String objectId, String attribute) {
            return serviceId + "|" + objectType + "|" + objectId + "|" + attribute;
        }
    }

    private static final class InMemoryPostingIdRepository implements TransitionCounterPostingIdRepository {
        private final Set<String> seen =
                java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

        @Override
        public List<TransitionCounterPosting> filterNew(List<TransitionCounterPosting> postings) {
            if (postings == null || postings.isEmpty()) {
                return List.of();
            }
            List<TransitionCounterPosting> accepted = new ArrayList<>();
            for (TransitionCounterPosting posting : postings) {
                if (seen.add(posting.postingId())) {
                    accepted.add(posting);
                }
            }
            return accepted.isEmpty() ? List.of() : List.copyOf(accepted);
        }
    }

    private static final class InMemoryRollupRepository implements TransitionCounterRollupRepository {
        private final Map<RollupKey, Long> counts = new LinkedHashMap<>();

        @Override
        public void applyBatch(CounterBucket bucket, List<RollupRow> rows) {
            for (RollupRow row : rows) {
                RollupKey key = new RollupKey(bucket.label(), row.timestamp(), row);
                counts.merge(key, row.delta(), Long::sum);
            }
        }

        long sum(
                CounterBucket bucket,
                UUID serviceId,
                String objectType,
                String attribute,
                String counterName,
                String fromState,
                String toState,
                Instant windowStart,
                Instant windowEnd) {
            long total = 0;
            for (Map.Entry<RollupKey, Long> entry : counts.entrySet()) {
                RollupKey key = entry.getKey();
                if (!bucket.label().equals(key.bucket)) {
                    continue;
                }
                RollupRow row = key.row;
                if (!row.serviceId().equals(serviceId)
                        || !row.objectType().equals(objectType)
                        || !row.attribute().equals(attribute)
                        || !row.counterName().equals(counterName)
                        || !row.fromState().equals(fromState)
                        || !row.toState().equals(toState)) {
                    continue;
                }
                Instant ts = row.timestamp();
                if (ts.isBefore(windowStart) || !ts.isBefore(windowEnd)) {
                    continue;
                }
                total += entry.getValue();
            }
            return total;
        }

        private record RollupKey(String bucket, Instant ts, RollupRow row) {}
    }

    private static final class InMemorySyntheticRecordRepository implements SyntheticTerminalRecordRepository {
        private final Map<String, SyntheticTerminalRecord> records = new LinkedHashMap<>();

        @Override
        public boolean insertIfEligible(SyntheticTerminalRecord record, Instant expectedLastEventTs) {
            if (record == null) {
                return false;
            }
            if (!record.lastEventTs().equals(expectedLastEventTs)) {
                return false;
            }
            records.put(record.syntheticEventId(), record);
            return true;
        }

        @Override
        public List<SyntheticTerminalRecord> findActive(
                UUID serviceId, String objectType, String objectId, String attribute) {
            List<SyntheticTerminalRecord> out = new ArrayList<>();
            for (SyntheticTerminalRecord record : records.values()) {
                if (record.serviceId().equals(serviceId)
                        && record.objectType().equals(objectType)
                        && record.objectId().equals(objectId)
                        && record.attribute().equals(attribute)
                        && "ACTIVE".equals(record.status())) {
                    out.add(record);
                }
            }
            return out;
        }

        @Override
        public boolean supersede(String syntheticEventId, String supersededByEventId, Instant supersededAt) {
            SyntheticTerminalRecord record = records.get(syntheticEventId);
            if (record == null || !"ACTIVE".equals(record.status())) {
                return false;
            }
            records.put(
                    syntheticEventId,
                    new SyntheticTerminalRecord(
                            record.serviceId(),
                            record.objectType(),
                            record.objectId(),
                            record.attribute(),
                            record.ruleId(),
                            record.syntheticEventId(),
                            record.syntheticTs(),
                            record.syntheticState(),
                            record.emitServiceId(),
                            record.reason(),
                            record.origin(),
                            "SUPERSEDED",
                            record.lastEventTs(),
                            record.lastState(),
                            supersededByEventId,
                            supersededAt,
                            record.reversedAt(),
                            record.transitionFootprint()));
            return true;
        }

        @Override
        public void markReversed(String syntheticEventId, Instant reversedAt) {}

        @Override
        public void recordFootprint(
                String syntheticEventId,
                List<com.obsinity.service.core.state.transition.counter.TransitionCounterFootprintEntry> entries) {}
    }

    private static final class InMemoryOutcomeRepository extends TransitionOutcomeRepository {
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
                Instant firstSeenTs) {}

        @Override
        public void recordSyntheticOutcome(
                UUID serviceId,
                String objectType,
                String objectId,
                String attribute,
                String terminalState,
                Instant terminalTs,
                String syntheticEventId) {}

        @Override
        public void recordObservedOutcome(
                UUID serviceId,
                String objectType,
                String objectId,
                String attribute,
                String terminalState,
                Instant terminalTs,
                String supersededByEventId) {}
    }

    private static final class NoopTelemetry implements TransitionTelemetry {
        @Override
        public void recordSyntheticInjection(String objectType, String ruleId, String state) {}

        @Override
        public void adjustSyntheticActive(String objectType, String ruleId, long delta) {}

        @Override
        public void recordSyntheticSuperseded(String objectType, String ruleId, java.time.Duration timeToSupersede) {}

        @Override
        public void recordFanoutTruncation(
                String objectType, String counterName, int originalSize, int truncatedSize) {}

        @Override
        public void recordSeenStatesCapExceeded(String objectType, String attribute, int cap) {}

        @Override
        public void recordPostingDedupHits(long hits) {}
    }

    private static final class TestCodec implements StateCodec {
        private final Map<String, Integer> stateToId = new LinkedHashMap<>();
        private final Map<Integer, String> idToState = new LinkedHashMap<>();

        @Override
        public int toId(UUID serviceId, String objectType, String attribute, String state) {
            return stateToId.computeIfAbsent(state, key -> {
                int id = stateToId.size();
                idToState.put(id, state);
                return id;
            });
        }

        @Override
        public String fromId(UUID serviceId, String objectType, String attribute, int id) {
            return idToState.get(id);
        }

        @Override
        public List<String> decode(UUID serviceId, String objectType, String attribute, java.util.BitSet bits) {
            List<String> out = new ArrayList<>();
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                String state = idToState.get(i);
                if (state != null) {
                    out.add(state);
                }
            }
            return out;
        }
    }

    private static TransactionTemplate noopTransactionTemplate() {
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        };
        return new TransactionTemplate(txManager);
    }
}

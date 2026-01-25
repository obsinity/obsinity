package com.obsinity.service.core.state.transition.inference;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.FromMode;
import com.obsinity.service.core.config.RegistrySnapshot;
import com.obsinity.service.core.config.ServiceConfig;
import com.obsinity.service.core.config.TransitionCounterDefinition;
import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.state.transition.counter.SeenStates;
import com.obsinity.service.core.state.transition.counter.TransitionCounterEvaluator;
import com.obsinity.service.core.state.transition.counter.TransitionCounterFootprintEntry;
import com.obsinity.service.core.state.transition.counter.TransitionCounterMetricKey;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPosting;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingIdFactory;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingIdRepository;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingService;
import com.obsinity.service.core.state.transition.counter.TransitionCounterRollupRepository;
import com.obsinity.service.core.state.transition.counter.TransitionCounterSnapshot;
import com.obsinity.service.core.state.transition.counter.TransitionCounterSnapshotStore;
import com.obsinity.service.core.state.transition.outcome.TransitionOutcomeRepository;
import com.obsinity.service.core.state.transition.outcome.TransitionOutcomeService;
import com.obsinity.service.core.state.transition.telemetry.TransitionTelemetry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TransitionSyntheticSupersedeServiceTest {

    @Test
    void replacementHappyPathAcrossBuckets() {
        TestHarness harness = new TestHarness();
        SyntheticTerminalRecord record = harness.seedSyntheticTerminal();

        Instant realTs = record.syntheticTs().plus(Duration.ofHours(2));
        EventEnvelope envelope = harness.realEvent("real-1", realTs, "FINISHED", "svc-A");

        boolean handled = harness.supersedeService.handleIfSuperseding(
                harness.serviceId, envelope, "Order", "o-1", "status", "FINISHED");

        assertThat(handled).isTrue();
        SyntheticTerminalRecord updated = harness.recordRepository.findBySyntheticEventId(record.syntheticEventId());
        assertThat(updated.status()).isEqualTo("SUPERSEDED");
        assertThat(updated.supersededByEventId()).isEqualTo("real-1");

        TransitionCounterMetricKey abandonedKey = new TransitionCounterMetricKey(
                harness.serviceId, "Order", "status", "to_ABANDONED", "CREATED", "ABANDONED");
        assertThat(harness.rollups.getCount(CounterBucket.S5, record.syntheticTs(), abandonedKey))
                .isEqualTo(0);

        TransitionCounterMetricKey finishedKey = new TransitionCounterMetricKey(
                harness.serviceId, "Order", "status", "to_FINISHED", "ABANDONED", "FINISHED");
        assertThat(harness.rollups.getCount(CounterBucket.S5, realTs, finishedKey))
                .isEqualTo(1);
    }

    @Test
    void supersedeIsIdempotent() {
        TestHarness harness = new TestHarness();
        SyntheticTerminalRecord record = harness.seedSyntheticTerminal();

        Instant realTs = record.syntheticTs().plus(Duration.ofHours(2));
        EventEnvelope envelope = harness.realEvent("real-1", realTs, "FINISHED", "svc-A");

        harness.supersedeService.handleIfSuperseding(harness.serviceId, envelope, "Order", "o-1", "status", "FINISHED");
        harness.supersedeService.handleIfSuperseding(harness.serviceId, envelope, "Order", "o-1", "status", "FINISHED");

        TransitionCounterMetricKey abandonedKey = new TransitionCounterMetricKey(
                harness.serviceId, "Order", "status", "to_ABANDONED", "CREATED", "ABANDONED");
        TransitionCounterMetricKey finishedKey = new TransitionCounterMetricKey(
                harness.serviceId, "Order", "status", "to_FINISHED", "ABANDONED", "FINISHED");

        assertThat(harness.rollups.getCount(CounterBucket.S5, record.syntheticTs(), abandonedKey))
                .isEqualTo(0);
        assertThat(harness.rollups.getCount(CounterBucket.S5, realTs, finishedKey))
                .isEqualTo(1);
    }

    @Test
    void concurrentSupersedeOnlyAppliesOnce() throws Exception {
        TestHarness harness = new TestHarness();
        SyntheticTerminalRecord record = harness.seedSyntheticTerminal();

        Instant realTs = record.syntheticTs().plus(Duration.ofHours(2));
        EventEnvelope one = harness.realEvent("real-1", realTs, "FINISHED", "svc-A");
        EventEnvelope two = harness.realEvent("real-2", realTs, "FINISHED", "svc-B");

        Callable<Boolean> callOne = () -> harness.supersedeService.handleIfSuperseding(
                harness.serviceId, one, "Order", "o-1", "status", "FINISHED");
        Callable<Boolean> callTwo = () -> harness.supersedeService.handleIfSuperseding(
                harness.serviceId, two, "Order", "o-1", "status", "FINISHED");

        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = executor.invokeAll(List.of(callOne, callTwo));
            boolean anyHandled = results.get(0).get() || results.get(1).get();
            assertThat(anyHandled).isTrue();
        } finally {
            executor.shutdownNow();
        }

        TransitionCounterMetricKey finishedKey = new TransitionCounterMetricKey(
                harness.serviceId, "Order", "status", "to_FINISHED", "ABANDONED", "FINISHED");
        assertThat(harness.rollups.getCount(CounterBucket.S5, realTs, finishedKey))
                .isEqualTo(1);
    }

    @Test
    void noOpWhenNoActiveSyntheticOrNonTerminal() {
        TestHarness harness = new TestHarness();

        Instant ts = Instant.parse("2025-01-01T02:00:00Z");
        EventEnvelope envelope = harness.realEvent("real-1", ts, "IN_PROGRESS", "svc-A");

        boolean handled = harness.supersedeService.handleIfSuperseding(
                harness.serviceId, envelope, "Order", "o-1", "status", "IN_PROGRESS");

        assertThat(handled).isFalse();
        assertThat(harness.recordRepository.records).isEmpty();
    }

    @Test
    void openCountersRemainStoppedAfterSupersede() {
        TestHarness harness = new TestHarness(true);
        SyntheticTerminalRecord record = harness.seedSyntheticTerminal();

        Instant realTs = record.syntheticTs().plus(Duration.ofHours(2));
        EventEnvelope envelope = harness.realEvent("real-1", realTs, "FINISHED", "svc-A");

        harness.supersedeService.handleIfSuperseding(harness.serviceId, envelope, "Order", "o-1", "status", "FINISHED");

        Instant later = realTs.plus(Duration.ofHours(1));
        harness.evaluator.evaluate(harness.serviceId, "follow-up", later, "Order", "o-1", "status", "IN_PROGRESS");

        TransitionCounterMetricKey openKey =
                new TransitionCounterMetricKey(harness.serviceId, "Order", "status", "open_orders", "ABANDONED", null);
        assertThat(harness.rollups.getCount(CounterBucket.S5, later, openKey)).isEqualTo(0);
    }

    @Test
    void telemetryRecordsSupersede() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        TestHarness harness = new TestHarness(false, telemetry);
        SyntheticTerminalRecord record = harness.seedSyntheticTerminal();

        Instant realTs = record.syntheticTs().plus(Duration.ofHours(2));
        EventEnvelope envelope = harness.realEvent("real-1", realTs, "FINISHED", "svc-A");

        harness.supersedeService.handleIfSuperseding(harness.serviceId, envelope, "Order", "o-1", "status", "FINISHED");

        assertThat(telemetry.superseded).isEqualTo(1);
    }

    private static final class TestHarness {
        private final UUID serviceId = UUID.randomUUID();
        private final InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
        private final InMemoryPostingIdRepository postingIds = new InMemoryPostingIdRepository();
        private final InMemoryRollupRepository rollups = new InMemoryRollupRepository();
        private final TransitionCounterPostingService postingService =
                new TransitionCounterPostingService(postingIds, rollups, noopTransactionTemplate());
        private final TransitionCounterPostingIdFactory postingIdFactory = new TransitionCounterPostingIdFactory();
        private final TransitionCounterEvaluator evaluator;
        private final InMemorySyntheticRecordRepository recordRepository = new InMemorySyntheticRecordRepository();
        private final TransitionOutcomeRepository outcomeRepository = new InMemoryOutcomeRepository();
        private final TransitionOutcomeService outcomeService;
        private final TransitionSyntheticSupersedeService supersedeService;
        private final TestCodec codec = new TestCodec();

        private TestHarness() {
            this(false, new NoopTelemetry());
        }

        private TestHarness(boolean includeOpenRule) {
            this(includeOpenRule, new NoopTelemetry());
        }

        private TestHarness(boolean includeOpenRule, TransitionTelemetry telemetry) {
            List<TransitionCounterDefinition> counters = new ArrayList<>();
            counters.add(new TransitionCounterDefinition(
                    "to_ABANDONED", "Order", "ABANDONED", FromMode.DEFAULT_LAST, List.of(), false));
            counters.add(new TransitionCounterDefinition(
                    "to_FINISHED", "Order", "FINISHED", FromMode.DEFAULT_LAST, List.of(), false));
            if (includeOpenRule) {
                counters.add(new TransitionCounterDefinition(
                        "open_orders", "Order", null, FromMode.ANY_SEEN, List.of(), true));
            }
            ServiceConfig svc =
                    new ServiceConfig(serviceId, "svc", Instant.now(), Map.of(), List.of(), counters, List.of());
            com.obsinity.service.core.config.ConfigRegistry registry =
                    new com.obsinity.service.core.config.ConfigRegistry();
            registry.swap(new RegistrySnapshot(Map.of(serviceId, svc), Instant.now()));
            ConfigLookup lookup = new ConfigLookup(registry);

            evaluator = new TransitionCounterEvaluator(
                    lookup,
                    snapshotStore,
                    postingService,
                    (svcId, type) -> Set.of("ABANDONED", "FINISHED"),
                    postingIdFactory,
                    telemetry,
                    codec);

            outcomeService =
                    new TransitionOutcomeService(outcomeRepository, (svcId, type) -> Set.of("ABANDONED", "FINISHED"));
            supersedeService = new TransitionSyntheticSupersedeService(
                    recordRepository,
                    evaluator,
                    postingService,
                    postingIdFactory,
                    (svcId, type) -> Set.of("ABANDONED", "FINISHED"),
                    outcomeService,
                    telemetry,
                    Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC));
        }

        private SyntheticTerminalRecord seedSyntheticTerminal() {
            Instant syntheticTs = Instant.parse("2025-01-01T01:00:00Z");
            SeenStates seenStates = SeenStates.empty(serviceId, "Order", "status");
            seenStates.add(codec, "CREATED");
            seenStates.add(codec, "ABANDONED");
            snapshotStore.upsert(
                    serviceId, "Order", "o-1", "status", "ABANDONED", seenStates, syntheticTs, "ABANDONED");

            TransitionCounterFootprintEntry entry =
                    new TransitionCounterFootprintEntry("to_ABANDONED", List.of("CREATED"), "ABANDONED");
            SyntheticTerminalRecord record = new SyntheticTerminalRecord(
                    serviceId,
                    "Order",
                    "o-1",
                    "status",
                    "rule-1",
                    "synthetic-1",
                    syntheticTs,
                    "ABANDONED",
                    "obsinity",
                    "TIMEOUT",
                    "SYNTHETIC",
                    "ACTIVE",
                    syntheticTs.minus(Duration.ofHours(1)),
                    "CREATED",
                    null,
                    null,
                    null,
                    List.of(entry));
            recordRepository.records.put(record.syntheticEventId(), record);

            TransitionCounterMetricKey key = new TransitionCounterMetricKey(
                    serviceId, "Order", "status", "to_ABANDONED", "CREATED", "ABANDONED");
            String postingId = postingIdFactory.build(record.syntheticEventId(), key, 1, syntheticTs);
            postingService.post(key, syntheticTs, 1, postingId);

            return record;
        }

        private EventEnvelope realEvent(String eventId, Instant ts, String state, String emittingServiceId) {
            Map<String, Object> attributes = Map.of("status", state);
            return EventEnvelope.builder()
                    .serviceId(emittingServiceId)
                    .eventType("order.event")
                    .eventId(eventId)
                    .timestamp(ts)
                    .ingestedAt(ts)
                    .attributes(attributes)
                    .build();
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

    private static final class InMemorySyntheticRecordRepository implements SyntheticTerminalRecordRepository {
        private final Map<String, SyntheticTerminalRecord> records = new LinkedHashMap<>();

        @Override
        public boolean insertIfEligible(SyntheticTerminalRecord record, Instant expectedLastEventTs) {
            if (records.containsKey(record.syntheticEventId())) {
                return false;
            }
            records.put(record.syntheticEventId(), record);
            return true;
        }

        @Override
        public void recordFootprint(String syntheticEventId, List<TransitionCounterFootprintEntry> entries) {}

        @Override
        public List<SyntheticTerminalRecord> findActive(
                UUID serviceId, String objectType, String objectId, String attribute) {
            List<SyntheticTerminalRecord> matches = new ArrayList<>();
            for (SyntheticTerminalRecord record : records.values()) {
                if (!record.serviceId().equals(serviceId)
                        || !record.objectType().equals(objectType)
                        || !record.objectId().equals(objectId)
                        || !record.attribute().equals(attribute)) {
                    continue;
                }
                if (!"ACTIVE".equals(record.status())) {
                    continue;
                }
                matches.add(record);
            }
            return matches;
        }

        @Override
        public synchronized boolean supersede(
                String syntheticEventId, String supersededByEventId, Instant supersededAt) {
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
        public void markReversed(String syntheticEventId, Instant reversedAt) {
            SyntheticTerminalRecord record = records.get(syntheticEventId);
            if (record == null) {
                return;
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
                            record.status(),
                            record.lastEventTs(),
                            record.lastState(),
                            record.supersededByEventId(),
                            record.supersededAt(),
                            reversedAt,
                            record.transitionFootprint()));
        }

        SyntheticTerminalRecord findBySyntheticEventId(String syntheticEventId) {
            return records.get(syntheticEventId);
        }
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
        public void recordSyntheticSuperseded(String objectType, String ruleId, Duration timeToSupersede) {}

        @Override
        public void recordFanoutTruncation(
                String objectType, String counterName, int originalSize, int truncatedSize) {}

        @Override
        public void recordSeenStatesCapExceeded(String objectType, String attribute, int cap) {}

        @Override
        public void recordPostingDedupHits(long hits) {}
    }

    private static final class RecordingTelemetry implements TransitionTelemetry {
        private long superseded;

        @Override
        public void recordSyntheticInjection(String objectType, String ruleId, String state) {}

        @Override
        public void adjustSyntheticActive(String objectType, String ruleId, long delta) {}

        @Override
        public void recordSyntheticSuperseded(String objectType, String ruleId, Duration timeToSupersede) {
            superseded++;
        }

        @Override
        public void recordFanoutTruncation(
                String objectType, String counterName, int originalSize, int truncatedSize) {}

        @Override
        public void recordSeenStatesCapExceeded(String objectType, String attribute, int cap) {}

        @Override
        public void recordPostingDedupHits(long hits) {}
    }

    private static final class TestCodec implements com.obsinity.service.core.state.transition.codec.StateCodec {
        private static final Map<String, Integer> stateToId = new java.util.concurrent.ConcurrentHashMap<>();
        private static final Map<Integer, String> idToState = new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.concurrent.atomic.AtomicInteger nextId =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public int toId(UUID serviceId, String objectType, String attribute, String state) {
            return stateToId.computeIfAbsent(state, key -> {
                int id = nextId.getAndIncrement();
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

    private static final class InMemoryPostingIdRepository implements TransitionCounterPostingIdRepository {
        private final Set<String> seen = new LinkedHashSet<>();

        @Override
        public List<TransitionCounterPosting> filterNew(List<TransitionCounterPosting> postings) {
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
                RollupKey key = new RollupKey(bucket.label(), row);
                counts.merge(key, row.delta(), Long::sum);
            }
        }

        long getCount(CounterBucket bucket, Instant ts, TransitionCounterMetricKey key) {
            Instant aligned = bucket.align(ts);
            RollupKey lookup = new RollupKey(
                    bucket.label(),
                    new RollupRow(
                            aligned,
                            key.serviceId(),
                            key.objectType(),
                            key.attribute(),
                            key.counterName(),
                            key.fromState(),
                            key.toState() != null ? key.toState() : "(open)",
                            0));
            return counts.getOrDefault(lookup, 0L);
        }

        private record RollupKey(String bucket, RollupRow row) {
            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof RollupKey other)) return false;
                return bucket.equals(other.bucket)
                        && row.timestamp().equals(other.row.timestamp())
                        && row.serviceId().equals(other.row.serviceId())
                        && row.objectType().equals(other.row.objectType())
                        && row.attribute().equals(other.row.attribute())
                        && row.counterName().equals(other.row.counterName())
                        && row.fromState().equals(other.row.fromState())
                        && row.toState().equals(other.row.toState());
            }

            @Override
            public int hashCode() {
                return java.util.Objects.hash(
                        bucket,
                        row.timestamp(),
                        row.serviceId(),
                        row.objectType(),
                        row.attribute(),
                        row.counterName(),
                        row.fromState(),
                        row.toState());
            }
        }
    }
}

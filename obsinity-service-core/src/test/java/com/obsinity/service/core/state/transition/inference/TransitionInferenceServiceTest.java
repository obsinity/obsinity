package com.obsinity.service.core.state.transition.inference;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.FromMode;
import com.obsinity.service.core.config.InferenceRuleDefinition;
import com.obsinity.service.core.config.RegistrySnapshot;
import com.obsinity.service.core.config.ServiceConfig;
import com.obsinity.service.core.config.TransitionCounterDefinition;
import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.state.transition.counter.SeenStates;
import com.obsinity.service.core.state.transition.counter.TerminalStateResolver;
import com.obsinity.service.core.state.transition.counter.TransitionCounterEvaluator;
import com.obsinity.service.core.state.transition.counter.TransitionCounterFootprintEntry;
import com.obsinity.service.core.state.transition.counter.TransitionCounterMetricKey;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingIdFactory;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingIdRepository;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingService;
import com.obsinity.service.core.state.transition.counter.TransitionCounterRollupRepository;
import com.obsinity.service.core.state.transition.counter.TransitionCounterSnapshot;
import com.obsinity.service.core.state.transition.counter.TransitionCounterSnapshotStore;
import com.obsinity.service.core.state.transition.outcome.TransitionOutcomeRepository;
import com.obsinity.service.core.state.transition.outcome.TransitionOutcomeService;
import com.obsinity.service.core.state.transition.telemetry.TransitionTelemetry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

class TransitionInferenceServiceTest {

    @Test
    void inferenceTriggersSyntheticEvent() {
        UUID serviceId = UUID.randomUUID();
        TransitionInferenceService service = buildInferenceService(serviceId);
        InMemorySnapshotStore snapshotStore = getSnapshotStore(service);
        InMemorySyntheticRecordRepository recordRepo = getRecordRepo(service);
        InMemoryRollupRepository rollups = getRollupRepo(service);

        Instant lastEventTs = Instant.parse("2025-01-01T00:00:00Z");
        snapshotStore.upsert(
                serviceId,
                "Order",
                "o-1",
                "status",
                "IN_PROGRESS",
                seenStates(serviceId, "Order", "status", "CREATED", "IN_PROGRESS"),
                lastEventTs,
                null);

        service.runOnce(lastEventTs.plus(Duration.ofHours(2)), 50);

        assertThat(recordRepo.records).hasSize(1);
        SyntheticTerminalRecord record = recordRepo.records.values().iterator().next();
        assertThat(record.syntheticState()).isEqualTo("ABANDONED");
        assertThat(record.syntheticTs()).isEqualTo(lastEventTs.plus(Duration.ofHours(1)));
        assertThat(record.status()).isEqualTo("ACTIVE");
        assertThat(record.origin()).isEqualTo("SYNTHETIC");

        TransitionCounterMetricKey key =
                new TransitionCounterMetricKey(serviceId, "Order", "status", "to_ABANDONED", "CREATED", "ABANDONED");
        long count = rollups.getCount(CounterBucket.S5, record.syntheticTs(), key);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void noInjectWhenTerminal() {
        UUID serviceId = UUID.randomUUID();
        TransitionInferenceService service = buildInferenceService(serviceId);
        InMemorySnapshotStore snapshotStore = getSnapshotStore(service);
        InMemorySyntheticRecordRepository recordRepo = getRecordRepo(service);

        Instant lastEventTs = Instant.parse("2025-01-01T00:00:00Z");
        snapshotStore.upsert(
                serviceId,
                "Order",
                "o-1",
                "status",
                "ABANDONED",
                seenStates(serviceId, "Order", "status", "CREATED", "ABANDONED"),
                lastEventTs,
                "ABANDONED");

        service.runOnce(lastEventTs.plus(Duration.ofHours(2)), 50);

        assertThat(recordRepo.records).isEmpty();
    }

    @Test
    void noInjectWhenIdleNotMetOrMissing() {
        UUID serviceId = UUID.randomUUID();
        TransitionInferenceService service = buildInferenceService(serviceId);
        InMemorySnapshotStore snapshotStore = getSnapshotStore(service);
        InMemorySyntheticRecordRepository recordRepo = getRecordRepo(service);

        Instant lastEventTs = Instant.parse("2025-01-01T00:30:00Z");
        snapshotStore.upsert(
                serviceId,
                "Order",
                "o-1",
                "status",
                "IN_PROGRESS",
                seenStates(serviceId, "Order", "status", "CREATED", "IN_PROGRESS"),
                lastEventTs,
                null);
        snapshotStore.upsert(
                serviceId,
                "Order",
                "o-2",
                "status",
                "IN_PROGRESS",
                seenStates(serviceId, "Order", "status", "CREATED"),
                null,
                null);

        service.runOnce(Instant.parse("2025-01-01T01:00:00Z"), 50);

        assertThat(recordRepo.records).isEmpty();
    }

    @Test
    void inferenceIsIdempotent() {
        UUID serviceId = UUID.randomUUID();
        TransitionInferenceService service = buildInferenceService(serviceId);
        InMemorySnapshotStore snapshotStore = getSnapshotStore(service);
        InMemorySyntheticRecordRepository recordRepo = getRecordRepo(service);
        InMemoryRollupRepository rollups = getRollupRepo(service);

        Instant lastEventTs = Instant.parse("2025-01-01T00:00:00Z");
        snapshotStore.upsert(
                serviceId,
                "Order",
                "o-1",
                "status",
                "IN_PROGRESS",
                seenStates(serviceId, "Order", "status", "CREATED", "IN_PROGRESS"),
                lastEventTs,
                null);

        Instant now = lastEventTs.plus(Duration.ofHours(2));
        service.runOnce(now, 50);
        service.runOnce(now, 50);

        assertThat(recordRepo.records).hasSize(1);
        TransitionCounterMetricKey key =
                new TransitionCounterMetricKey(serviceId, "Order", "status", "to_ABANDONED", "CREATED", "ABANDONED");
        long count = rollups.getCount(CounterBucket.S5, lastEventTs.plus(Duration.ofHours(1)), key);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void footprintCapturedMatchesFromStates() {
        UUID serviceId = UUID.randomUUID();
        TransitionInferenceService service = buildInferenceService(serviceId);
        InMemorySnapshotStore snapshotStore = getSnapshotStore(service);
        InMemorySyntheticRecordRepository recordRepo = getRecordRepo(service);

        Instant lastEventTs = Instant.parse("2025-01-01T00:00:00Z");
        snapshotStore.upsert(
                serviceId,
                "Order",
                "o-1",
                "status",
                "IN_PROGRESS",
                seenStates(serviceId, "Order", "status", "CREATED", "REVIEW", "IN_PROGRESS"),
                lastEventTs,
                null);

        service.runOnce(lastEventTs.plus(Duration.ofHours(2)), 50);

        List<TransitionCounterFootprintEntry> entries = recordRepo.footprints.get(
                recordRepo.records.values().iterator().next().syntheticEventId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).fromStates()).containsExactlyInAnyOrder("CREATED", "REVIEW", "IN_PROGRESS");
    }

    @Test
    void integrationHappyPathStopsOpenCountsAfterSyntheticTerminal() {
        UUID serviceId = UUID.randomUUID();
        TransitionInferenceService service = buildInferenceService(serviceId, true);
        InMemorySnapshotStore snapshotStore = getSnapshotStore(service);
        InMemoryRollupRepository rollups = getRollupRepo(service);
        TransitionCounterEvaluator evaluator = getEvaluator(service);

        Instant startTs = Instant.parse("2025-01-01T00:00:00Z");
        snapshotStore.upsert(
                serviceId,
                "Order",
                "o-1",
                "status",
                "CREATED",
                seenStates(serviceId, "Order", "status", "CREATED"),
                startTs,
                null);

        service.runOnce(startTs.plus(Duration.ofHours(2)), 50);

        TransitionCounterMetricKey openKey =
                new TransitionCounterMetricKey(serviceId, "Order", "status", "open_orders", "CREATED", null);
        long openCount = rollups.getCount(CounterBucket.S5, startTs.plus(Duration.ofHours(1)), openKey);
        assertThat(openCount).isEqualTo(0);

        evaluator.evaluate(
                serviceId,
                UUID.randomUUID().toString(),
                startTs.plus(Duration.ofHours(3)),
                "Order",
                "o-1",
                "status",
                "IN_PROGRESS");
        long openCountAfter = rollups.getCount(CounterBucket.S5, startTs.plus(Duration.ofHours(3)), openKey);
        assertThat(openCountAfter).isEqualTo(0);
    }

    @Test
    void telemetryIncrementsOnSyntheticInjection() {
        UUID serviceId = UUID.randomUUID();
        RecordingTelemetry telemetry = new RecordingTelemetry();
        TransitionInferenceService service = buildInferenceService(serviceId, false, telemetry);
        InMemorySnapshotStore snapshotStore = getSnapshotStore(service);

        Instant lastEventTs = Instant.parse("2025-01-01T00:00:00Z");
        snapshotStore.upsert(
                serviceId,
                "Order",
                "o-1",
                "status",
                "IN_PROGRESS",
                seenStates(serviceId, "Order", "status", "CREATED", "IN_PROGRESS"),
                lastEventTs,
                null);

        service.runOnce(lastEventTs.plus(Duration.ofHours(2)), 50);

        assertThat(telemetry.syntheticInjections).isEqualTo(1);
        assertThat(telemetry.syntheticActive).isEqualTo(1);
    }

    private TransitionInferenceService buildInferenceService(UUID serviceId) {
        return buildInferenceService(serviceId, false);
    }

    private TransitionInferenceService buildInferenceService(UUID serviceId, boolean includeOpenRule) {
        return buildInferenceService(serviceId, includeOpenRule, new NoopTelemetry());
    }

    private TransitionInferenceService buildInferenceService(
            UUID serviceId, boolean includeOpenRule, TransitionTelemetry telemetry) {
        TransitionCounterDefinition terminalRule = new TransitionCounterDefinition(
                "to_ABANDONED", "Order", "ABANDONED", FromMode.ANY_SEEN, List.of(), false);
        List<TransitionCounterDefinition> counters = new ArrayList<>();
        counters.add(terminalRule);
        if (includeOpenRule) {
            counters.add(
                    new TransitionCounterDefinition("open_orders", "Order", null, FromMode.ANY_SEEN, List.of(), true));
        }
        InferenceRuleDefinition inferenceRule = new InferenceRuleDefinition(
                "order.idle", "Order", true, Duration.ofHours(1), "ABANDONED", "obsinity", "TIMEOUT");
        ServiceConfig svc = new ServiceConfig(
                serviceId, "svc", Instant.now(), Map.of(), List.of(), counters, List.of(inferenceRule));
        com.obsinity.service.core.config.ConfigRegistry registry =
                new com.obsinity.service.core.config.ConfigRegistry();
        registry.swap(new RegistrySnapshot(Map.of(serviceId, svc), Instant.now()));
        ConfigLookup lookup = new ConfigLookup(registry);

        InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
        InMemoryPostingIdRepository postingIds = new InMemoryPostingIdRepository();
        InMemoryRollupRepository rollups = new InMemoryRollupRepository();
        TransitionCounterPostingService postingService =
                new TransitionCounterPostingService(postingIds, rollups, noopTransactionTemplate());
        TerminalStateResolver terminalResolver = (svcId, type) -> Set.of("ABANDONED");
        TransitionCounterEvaluator evaluator = new TransitionCounterEvaluator(
                lookup,
                snapshotStore,
                postingService,
                terminalResolver,
                new TransitionCounterPostingIdFactory(),
                telemetry,
                CODEC);
        InMemorySyntheticRecordRepository recordRepository = new InMemorySyntheticRecordRepository();
        SyntheticEventIdFactory eventIdFactory = new SyntheticEventIdFactory();
        TransitionOutcomeRepository outcomeRepository = new InMemoryOutcomeRepository();
        TransitionOutcomeService outcomeService = new TransitionOutcomeService(outcomeRepository, terminalResolver);

        TransitionInferenceService inferenceService = new TransitionInferenceService(
                registry, snapshotStore, recordRepository, evaluator, eventIdFactory, outcomeService, telemetry);
        inferenceServiceRefs.put(
                inferenceService, new ServiceRefs(snapshotStore, recordRepository, rollups, evaluator));
        return inferenceService;
    }

    private static final Map<TransitionInferenceService, ServiceRefs> inferenceServiceRefs = new LinkedHashMap<>();
    private static final TestCodec CODEC = new TestCodec();

    private InMemorySnapshotStore getSnapshotStore(TransitionInferenceService service) {
        return inferenceServiceRefs.get(service).snapshotStore;
    }

    private InMemorySyntheticRecordRepository getRecordRepo(TransitionInferenceService service) {
        return inferenceServiceRefs.get(service).recordRepository;
    }

    private InMemoryRollupRepository getRollupRepo(TransitionInferenceService service) {
        return inferenceServiceRefs.get(service).rollups;
    }

    private TransitionCounterEvaluator getEvaluator(TransitionInferenceService service) {
        return inferenceServiceRefs.get(service).evaluator;
    }

    private static SeenStates seenStates(UUID serviceId, String objectType, String attribute, String... states) {
        SeenStates seenStates = SeenStates.empty(serviceId, objectType, attribute);
        if (states != null) {
            for (String state : states) {
                seenStates.add(CODEC, state);
            }
        }
        return seenStates;
    }

    private record ServiceRefs(
            InMemorySnapshotStore snapshotStore,
            InMemorySyntheticRecordRepository recordRepository,
            InMemoryRollupRepository rollups,
            TransitionCounterEvaluator evaluator) {}

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

    private static final class InMemorySnapshotStore
            implements TransitionCounterSnapshotStore, TransitionInferenceCandidateRepository {
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
                com.obsinity.service.core.state.transition.counter.SeenStates seenStates,
                Instant lastEventTs,
                String terminalState) {
            snapshots.put(
                    key(serviceId, objectType, objectId, attribute),
                    new TransitionCounterSnapshot(lastState, seenStates, lastEventTs, terminalState));
        }

        @Override
        public List<TransitionInferenceCandidate> findEligible(
                UUID serviceId, String objectType, Instant cutoff, int limit) {
            List<TransitionInferenceCandidate> results = new ArrayList<>();
            for (Map.Entry<String, TransitionCounterSnapshot> entry : snapshots.entrySet()) {
                String[] parts = entry.getKey().split("\\|", -1);
                UUID snapServiceId = UUID.fromString(parts[0]);
                if (!snapServiceId.equals(serviceId)) {
                    continue;
                }
                String snapObjectType = parts[1];
                if (!snapObjectType.equals(objectType)) {
                    continue;
                }
                TransitionCounterSnapshot snapshot = entry.getValue();
                if (snapshot.lastEventTs() == null) {
                    continue;
                }
                if (snapshot.terminalState() != null) {
                    continue;
                }
                if (snapshot.lastEventTs().isAfter(cutoff)) {
                    continue;
                }
                results.add(new TransitionInferenceCandidate(
                        snapServiceId,
                        snapObjectType,
                        parts[2],
                        parts[3],
                        snapshot.lastState(),
                        snapshot.lastEventTs(),
                        snapshot.terminalState()));
            }
            return results.size() > limit ? results.subList(0, limit) : results;
        }

        private String key(UUID serviceId, String objectType, String objectId, String attribute) {
            return serviceId + "|" + objectType + "|" + objectId + "|" + attribute;
        }
    }

    private static final class InMemorySyntheticRecordRepository implements SyntheticTerminalRecordRepository {
        private final Map<String, SyntheticTerminalRecord> records = new LinkedHashMap<>();
        private final Map<String, List<TransitionCounterFootprintEntry>> footprints = new LinkedHashMap<>();

        @Override
        public boolean insertIfEligible(SyntheticTerminalRecord record, Instant expectedLastEventTs) {
            if (record.lastEventTs() == null || !record.lastEventTs().equals(expectedLastEventTs)) {
                return false;
            }
            String key = record.serviceId()
                    + "|"
                    + record.objectType()
                    + "|"
                    + record.objectId()
                    + "|"
                    + record.attribute()
                    + "|"
                    + record.ruleId()
                    + "|"
                    + record.syntheticTs().toEpochMilli();
            if (records.containsKey(key)) {
                return false;
            }
            records.put(key, record);
            return true;
        }

        @Override
        public void recordFootprint(String syntheticEventId, List<TransitionCounterFootprintEntry> entries) {
            footprints.put(syntheticEventId, entries == null ? List.of() : List.copyOf(entries));
        }

        @Override
        public List<SyntheticTerminalRecord> findActive(
                UUID serviceId, String objectType, String objectId, String attribute) {
            List<SyntheticTerminalRecord> matches = new ArrayList<>();
            for (SyntheticTerminalRecord record : records.values()) {
                if (!record.serviceId().equals(serviceId)) {
                    continue;
                }
                if (!record.objectType().equals(objectType)) {
                    continue;
                }
                if (!record.objectId().equals(objectId)) {
                    continue;
                }
                if (!record.attribute().equals(attribute)) {
                    continue;
                }
                if (!"ACTIVE".equals(record.status())) {
                    continue;
                }
                List<TransitionCounterFootprintEntry> entryFootprint =
                        footprints.getOrDefault(record.syntheticEventId(), List.of());
                matches.add(new SyntheticTerminalRecord(
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
                        record.reversedAt(),
                        entryFootprint));
            }
            return matches;
        }

        @Override
        public boolean supersede(String syntheticEventId, String supersededByEventId, Instant supersededAt) {
            for (Map.Entry<String, SyntheticTerminalRecord> entry : records.entrySet()) {
                SyntheticTerminalRecord record = entry.getValue();
                if (!record.syntheticEventId().equals(syntheticEventId)) {
                    continue;
                }
                if (!"ACTIVE".equals(record.status())) {
                    return false;
                }
                SyntheticTerminalRecord updated = new SyntheticTerminalRecord(
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
                        record.transitionFootprint());
                entry.setValue(updated);
                return true;
            }
            return false;
        }

        @Override
        public void markReversed(String syntheticEventId, Instant reversedAt) {
            for (Map.Entry<String, SyntheticTerminalRecord> entry : records.entrySet()) {
                SyntheticTerminalRecord record = entry.getValue();
                if (!record.syntheticEventId().equals(syntheticEventId)) {
                    continue;
                }
                SyntheticTerminalRecord updated = new SyntheticTerminalRecord(
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
                        record.transitionFootprint());
                entry.setValue(updated);
                return;
            }
        }
    }

    private static final class InMemoryPostingIdRepository implements TransitionCounterPostingIdRepository {
        private final Set<String> seen = new LinkedHashSet<>();

        @Override
        public List<com.obsinity.service.core.state.transition.counter.TransitionCounterPosting> filterNew(
                List<com.obsinity.service.core.state.transition.counter.TransitionCounterPosting> postings) {
            List<com.obsinity.service.core.state.transition.counter.TransitionCounterPosting> accepted =
                    new ArrayList<>();
            for (com.obsinity.service.core.state.transition.counter.TransitionCounterPosting posting : postings) {
                if (seen.add(posting.postingId())) {
                    accepted.add(posting);
                }
            }
            return accepted.isEmpty() ? List.of() : List.copyOf(accepted);
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
        private long syntheticInjections;
        private long syntheticActive;

        @Override
        public void recordSyntheticInjection(String objectType, String ruleId, String state) {
            syntheticInjections++;
        }

        @Override
        public void adjustSyntheticActive(String objectType, String ruleId, long delta) {
            syntheticActive += delta;
        }

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

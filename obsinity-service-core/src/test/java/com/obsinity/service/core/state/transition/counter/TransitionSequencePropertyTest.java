package com.obsinity.service.core.state.transition.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.FromMode;
import com.obsinity.service.core.config.RegistrySnapshot;
import com.obsinity.service.core.config.ServiceConfig;
import com.obsinity.service.core.config.TransitionCounterDefinition;
import com.obsinity.service.core.state.transition.telemetry.TransitionTelemetry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransitionSequencePropertyTest {

    @Test
    void randomizedSequenceSameStateProducesNoPostings() {
        UUID serviceId = UUID.randomUUID();
        TestCodec codec = new TestCodec();
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(serviceId, sink, store, codec);

        List<String> states = List.of("STARTED", "IN_PROGRESS", "DONE", "ABANDONED");
        Random random = new Random(42);
        String lastState = null;

        for (int i = 0; i < 200; i++) {
            String state = states.get(random.nextInt(states.size()));
            int before = sink.postings.size();
            evaluator.evaluate(
                    serviceId,
                    "evt-" + i,
                    Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i),
                    "Order",
                    "o-1",
                    "status",
                    state);
            int after = sink.postings.size();
            if (lastState != null && lastState.equals(state)) {
                assertThat(after).isEqualTo(before);
            }
            lastState = state;
        }
    }

    @Test
    void replaySequenceIsIdempotentOnRollups() {
        UUID serviceId = UUID.randomUUID();
        TestCodec codec = new TestCodec();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        InMemoryPostingIdRepository postingIds = new InMemoryPostingIdRepository();
        InMemoryRollupRepository rollups = new InMemoryRollupRepository();
        TransitionCounterPostingService postingService =
                new TransitionCounterPostingService(postingIds, rollups, noopTransactionTemplate());
        TransitionCounterEvaluator evaluator = evaluatorWithRules(serviceId, postingService, store, codec);

        List<String> states = List.of("STARTED", "IN_PROGRESS", "DONE", "ABANDONED");
        Random random = new Random(7);
        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            sequence.add(states.get(random.nextInt(states.size())));
        }

        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        runSequence(evaluator, serviceId, base, sequence);
        Map<InMemoryRollupRepository.RollupKey, Long> before = rollups.snapshot();

        InMemorySnapshotStore replayStore = new InMemorySnapshotStore();
        TransitionCounterEvaluator replayEvaluator = evaluatorWithRules(serviceId, postingService, replayStore, codec);
        runSequence(replayEvaluator, serviceId, base, sequence);
        Map<InMemoryRollupRepository.RollupKey, Long> after = rollups.snapshot();

        assertThat(after).isEqualTo(before);
    }

    private void runSequence(TransitionCounterEvaluator evaluator, UUID serviceId, Instant base, List<String> states) {
        for (int i = 0; i < states.size(); i++) {
            evaluator.evaluate(serviceId, "evt-" + i, base.plusSeconds(i), "Order", "o-1", "status", states.get(i));
        }
    }

    private TransitionCounterEvaluator evaluatorWithRules(
            UUID serviceId, TransitionCounterPostingSink sink, TransitionCounterSnapshotStore store, TestCodec codec) {
        List<TransitionCounterDefinition> rules = List.of(
                new TransitionCounterDefinition("to_DONE", "Order", "DONE", FromMode.DEFAULT_LAST, List.of(), false),
                new TransitionCounterDefinition(
                        "to_ABANDONED", "Order", "ABANDONED", FromMode.DEFAULT_LAST, List.of(), false),
                new TransitionCounterDefinition("open_orders", "Order", null, FromMode.ANY_SEEN, List.of(), true));
        ServiceConfig svc = new ServiceConfig(serviceId, "svc", Instant.now(), Map.of(), List.of(), rules, List.of());
        RegistrySnapshot snapshot = new RegistrySnapshot(Map.of(serviceId, svc), Instant.now());
        com.obsinity.service.core.config.ConfigRegistry registry =
                new com.obsinity.service.core.config.ConfigRegistry();
        registry.swap(snapshot);
        ConfigLookup lookup = new ConfigLookup(registry);
        return new TransitionCounterEvaluator(
                lookup,
                store,
                sink,
                (svcId, type) -> Set.of("DONE", "ABANDONED"),
                new TransitionCounterPostingIdFactory(),
                new NoopTelemetry(),
                codec);
    }

    private static org.springframework.transaction.support.TransactionTemplate noopTransactionTemplate() {
        org.springframework.transaction.PlatformTransactionManager txManager =
                new org.springframework.transaction.PlatformTransactionManager() {
                    @Override
                    public org.springframework.transaction.TransactionStatus getTransaction(
                            org.springframework.transaction.TransactionDefinition definition) {
                        return new org.springframework.transaction.support.SimpleTransactionStatus();
                    }

                    @Override
                    public void commit(org.springframework.transaction.TransactionStatus status) {}

                    @Override
                    public void rollback(org.springframework.transaction.TransactionStatus status) {}
                };
        return new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    private static final class RecordingPostingSink implements TransitionCounterPostingSink {
        private final List<TransitionCounterPosting> postings = new ArrayList<>();

        @Override
        public void post(TransitionCounterMetricKey key, Instant timestamp, long delta, String postingId) {
            postings.add(new TransitionCounterPosting(key, timestamp, delta, postingId));
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
        private final java.util.Set<String> seen =
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
        public void applyBatch(com.obsinity.service.core.counter.CounterBucket bucket, List<RollupRow> rows) {
            for (RollupRow row : rows) {
                RollupKey key = new RollupKey(bucket.label(), row.timestamp(), row);
                counts.merge(key, row.delta(), Long::sum);
            }
        }

        Map<RollupKey, Long> snapshot() {
            return new LinkedHashMap<>(counts);
        }

        private record RollupKey(String bucket, Instant ts, RollupRow row) {
            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof RollupKey other)) return false;
                return bucket.equals(other.bucket)
                        && ts.equals(other.ts)
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
                        ts,
                        row.serviceId(),
                        row.objectType(),
                        row.attribute(),
                        row.counterName(),
                        row.fromState(),
                        row.toState());
            }
        }
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
}

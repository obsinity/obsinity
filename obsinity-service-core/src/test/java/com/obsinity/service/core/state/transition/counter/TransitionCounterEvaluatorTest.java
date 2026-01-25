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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class TransitionCounterEvaluatorTest {

    @Test
    void sameStateEventEmitsNothing() {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        TerminalStateResolver terminalResolver = (svc, type) -> Set.of();
        UUID serviceId = UUID.randomUUID();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "to_ACTIVE", "User", "ACTIVE", FromMode.DEFAULT_LAST, List.of(), false)),
                sink,
                store,
                terminalResolver);

        SeenStates activeSeen = SeenStates.empty(serviceId, "User", "status");
        activeSeen.add(new TestCodec(), "ACTIVE");
        store.upsert(serviceId, "User", "1", "status", "ACTIVE", activeSeen, Instant.now(), null);
        evaluator.evaluate(serviceId, "evt-1", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "ACTIVE");

        assertThat(sink.postings).isEmpty();
    }

    @Test
    void defaultLastEmitsSingleFromState() {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        UUID serviceId = UUID.randomUUID();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "to_ACTIVE", "User", "ACTIVE", FromMode.DEFAULT_LAST, List.of(), false)),
                sink,
                store,
                (svc, type) -> Set.of());

        SeenStates pendingSeen = SeenStates.empty(serviceId, "User", "status");
        pendingSeen.add(new TestCodec(), "PENDING");
        store.upsert(serviceId, "User", "1", "status", "PENDING", pendingSeen, Instant.now(), null);
        evaluator.evaluate(serviceId, "evt-2", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "ACTIVE");

        assertThat(sink.postings).hasSize(1);
        TransitionCounterPosting posting = sink.postings.get(0);
        assertThat(posting.key().fromState()).isEqualTo("PENDING");
        assertThat(posting.key().toState()).isEqualTo("ACTIVE");
    }

    @Test
    void defaultLastEmitsInitialWhenNoPriorState() {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        UUID serviceId = UUID.randomUUID();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "to_ACTIVE", "User", "ACTIVE", FromMode.DEFAULT_LAST, List.of(), false)),
                sink,
                store,
                (svc, type) -> Set.of());

        evaluator.evaluate(serviceId, "evt-0", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "ACTIVE");

        assertThat(sink.postings).hasSize(1);
        assertThat(sink.postings.get(0).key().fromState()).isNull();
    }

    @Test
    void anySeenEmitsAllPriorStates() {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        UUID serviceId = UUID.randomUUID();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "to_ACTIVE", "User", "ACTIVE", FromMode.ANY_SEEN, List.of(), false)),
                sink,
                store,
                (svc, type) -> Set.of());

        SeenStates anySeen = SeenStates.empty(serviceId, "User", "status");
        anySeen.add(new TestCodec(), "PENDING");
        anySeen.add(new TestCodec(), "SUSPENDED");
        store.upsert(serviceId, "User", "1", "status", "SUSPENDED", anySeen, Instant.now(), null);
        evaluator.evaluate(serviceId, "evt-3", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "ACTIVE");

        assertThat(sink.postings).hasSize(2);
        assertThat(sink.postings)
                .extracting(p -> p.key().fromState())
                .containsExactlyInAnyOrder("PENDING", "SUSPENDED");
    }

    @Test
    void subsetFiltersSeenStates() {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        UUID serviceId = UUID.randomUUID();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "to_DONE", "User", "DONE", FromMode.SUBSET, List.of("PENDING", "BLOCKED"), false)),
                sink,
                store,
                (svc, type) -> Set.of());

        SeenStates subsetSeen = SeenStates.empty(serviceId, "User", "status");
        subsetSeen.add(new TestCodec(), "PENDING");
        subsetSeen.add(new TestCodec(), "ACTIVE");
        store.upsert(serviceId, "User", "1", "status", "ACTIVE", subsetSeen, Instant.now(), null);
        evaluator.evaluate(serviceId, "evt-4", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "DONE");

        assertThat(sink.postings).hasSize(1);
        assertThat(sink.postings.get(0).key().fromState()).isEqualTo("PENDING");
    }

    @Test
    void anySeenWithEmptySeenStatesEmitsNothing() {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        UUID serviceId = UUID.randomUUID();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "to_ACTIVE", "User", "ACTIVE", FromMode.ANY_SEEN, List.of(), false)),
                sink,
                store,
                (svc, type) -> Set.of());

        evaluator.evaluate(serviceId, "evt-5", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "ACTIVE");

        assertThat(sink.postings).isEmpty();
    }

    @Test
    void untilTerminalEmitsOnlyWhileOpenAndStopsAfterTerminal() {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        TerminalStateResolver terminalResolver = (svc, type) -> Set.of("DONE");
        UUID serviceId = UUID.randomUUID();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "open_objects", "User", null, FromMode.DEFAULT_LAST, List.of(), true)),
                sink,
                store,
                terminalResolver);

        SeenStates openSeen = SeenStates.empty(serviceId, "User", "status");
        openSeen.add(new TestCodec(), "PENDING");
        store.upsert(serviceId, "User", "1", "status", "PENDING", openSeen, Instant.now(), null);
        evaluator.evaluate(serviceId, "evt-6", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "ACTIVE");
        assertThat(sink.postings).hasSize(1);
        assertThat(sink.postings.get(0).key().toState()).isNull();

        sink.postings.clear();
        evaluator.evaluate(serviceId, "evt-7", Instant.parse("2025-01-01T00:00:07Z"), "User", "1", "status", "DONE");
        assertThat(sink.postings).isEmpty();

        sink.postings.clear();
        evaluator.evaluate(serviceId, "evt-8", Instant.parse("2025-01-01T00:00:12Z"), "User", "1", "status", "ACTIVE");
        assertThat(sink.postings).isEmpty();
    }

    @Test
    void anySeenTruncatesWhenGuardrailEnabled() throws Exception {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        UUID serviceId = UUID.randomUUID();
        RecordingTelemetry telemetry = new RecordingTelemetry();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "to_ACTIVE", "User", "ACTIVE", FromMode.ANY_SEEN, List.of(), false)),
                sink,
                store,
                (svc, type) -> Set.of(),
                telemetry,
                new TestCodec());

        SeenStates capSeen = SeenStates.empty(serviceId, "User", "status");
        capSeen.add(new TestCodec(), "PENDING");
        capSeen.add(new TestCodec(), "SUSPENDED");
        capSeen.add(new TestCodec(), "ACTIVE");
        store.upsert(serviceId, "User", "1", "status", "SUSPENDED", capSeen, Instant.now(), null);

        var field = TransitionCounterEvaluator.class.getDeclaredField("maxFromStates");
        field.setAccessible(true);
        field.set(evaluator, 2);

        evaluator.evaluate(serviceId, "evt-9", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "ACTIVE");

        assertThat(sink.postings).hasSize(2);
        assertThat(telemetry.truncations).hasSize(1);
    }

    @Test
    void seenStatesCapPreventsGrowth() throws Exception {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        UUID serviceId = UUID.randomUUID();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "to_ACTIVE", "User", "ACTIVE", FromMode.DEFAULT_LAST, List.of(), false)),
                sink,
                store,
                (svc, type) -> Set.of());

        SeenStates seenStates = SeenStates.empty(serviceId, "User", "status");
        seenStates.add(new TestCodec(), "PENDING");
        store.upsert(serviceId, "User", "1", "status", "PENDING", seenStates, Instant.now(), null);

        var field = TransitionCounterEvaluator.class.getDeclaredField("maxSeenStates");
        field.setAccessible(true);
        field.set(evaluator, 1);

        evaluator.evaluate(serviceId, "evt-10", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "ACTIVE");

        TransitionCounterSnapshot snapshot = store.find(serviceId, "User", "1", "status");
        assertThat(snapshot.seenStates().size()).isEqualTo(1);
    }

    @Test
    void anySeenLargeSeenStatesIsCapped() throws Exception {
        RecordingPostingSink sink = new RecordingPostingSink();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        UUID serviceId = UUID.randomUUID();
        RecordingTelemetry telemetry = new RecordingTelemetry();
        TransitionCounterEvaluator evaluator = evaluatorWithRules(
                serviceId,
                List.of(new TransitionCounterDefinition(
                        "to_ACTIVE", "User", "ACTIVE", FromMode.ANY_SEEN, List.of(), false)),
                sink,
                store,
                (svc, type) -> Set.of(),
                telemetry,
                new TestCodec());

        SeenStates seenStates = SeenStates.empty(serviceId, "User", "status");
        for (int i = 0; i < 200; i++) {
            seenStates.add(new TestCodec(), "STATE_" + i);
        }
        store.upsert(serviceId, "User", "1", "status", "STATE_199", seenStates, Instant.now(), null);

        var field = TransitionCounterEvaluator.class.getDeclaredField("maxFromStates");
        field.setAccessible(true);
        field.set(evaluator, 10);

        evaluator.evaluate(serviceId, "evt-11", Instant.parse("2025-01-01T00:00:02Z"), "User", "1", "status", "ACTIVE");

        assertThat(sink.postings).hasSize(10);
        assertThat(telemetry.truncations).hasSize(1);
    }

    private TransitionCounterEvaluator evaluatorWithRules(
            UUID serviceId,
            List<TransitionCounterDefinition> rules,
            TransitionCounterPostingSink sink,
            TransitionCounterSnapshotStore store,
            TerminalStateResolver terminalStateResolver,
            TransitionTelemetry telemetry,
            com.obsinity.service.core.state.transition.codec.StateCodec codec) {
        ServiceConfig svc = new ServiceConfig(serviceId, "svc", Instant.now(), Map.of(), List.of(), rules, List.of());
        RegistrySnapshot snapshot = new RegistrySnapshot(Map.of(serviceId, svc), Instant.now());
        com.obsinity.service.core.config.ConfigRegistry registry =
                new com.obsinity.service.core.config.ConfigRegistry();
        registry.swap(snapshot);
        ConfigLookup lookup = new ConfigLookup(registry);
        return new TransitionCounterEvaluator(
                lookup, store, sink, terminalStateResolver, new TransitionCounterPostingIdFactory(), telemetry, codec);
    }

    private TransitionCounterEvaluator evaluatorWithRules(
            UUID serviceId,
            List<TransitionCounterDefinition> rules,
            TransitionCounterPostingSink sink,
            TransitionCounterSnapshotStore store,
            TerminalStateResolver terminalStateResolver) {
        return evaluatorWithRules(
                serviceId, rules, sink, store, terminalStateResolver, new RecordingTelemetry(), new TestCodec());
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

    private static final class RecordingTelemetry implements TransitionTelemetry {
        private final List<String> truncations = new ArrayList<>();

        @Override
        public void recordSyntheticInjection(String objectType, String ruleId, String state) {}

        @Override
        public void adjustSyntheticActive(String objectType, String ruleId, long delta) {}

        @Override
        public void recordSyntheticSuperseded(String objectType, String ruleId, java.time.Duration timeToSupersede) {}

        @Override
        public void recordFanoutTruncation(String objectType, String counterName, int originalSize, int truncatedSize) {
            truncations.add(objectType + ":" + counterName + ":" + originalSize + ":" + truncatedSize);
        }

        @Override
        public void recordSeenStatesCapExceeded(String objectType, String attribute, int cap) {}

        @Override
        public void recordPostingDedupHits(long hits) {}
    }

    private static final class TestCodec implements com.obsinity.service.core.state.transition.codec.StateCodec {
        private static final Map<String, Integer> stateToId = new ConcurrentHashMap<>();
        private static final Map<Integer, String> idToState = new ConcurrentHashMap<>();
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
}

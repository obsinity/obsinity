package com.obsinity.service.core.state.transition.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.CounterGranularity;
import java.time.Instant;
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

class TransitionCounterPostingServiceTest {

    @Test
    void postingIsIdempotentPerPostingId() {
        InMemoryPostingIdRepository postingIds = new InMemoryPostingIdRepository();
        InMemoryRollupRepository rollups = new InMemoryRollupRepository();
        TransitionCounterPostingService service =
                new TransitionCounterPostingService(postingIds, rollups, noopTransactionTemplate());

        UUID serviceId = UUID.randomUUID();
        TransitionCounterMetricKey key =
                new TransitionCounterMetricKey(serviceId, "User", "status", "to_ACTIVE", "PENDING", "ACTIVE");
        Instant ts = Instant.parse("2025-01-01T00:00:02Z");

        service.post(key, ts, 1, "posting-1");
        service.post(key, ts, 1, "posting-1");

        long count = rollups.getCount(CounterBucket.S5, ts, key);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void backdatedPostingsAlignToEventTimestamp() {
        InMemoryPostingIdRepository postingIds = new InMemoryPostingIdRepository();
        InMemoryRollupRepository rollups = new InMemoryRollupRepository();
        TransitionCounterPostingService service =
                new TransitionCounterPostingService(postingIds, rollups, noopTransactionTemplate());

        UUID serviceId = UUID.randomUUID();
        TransitionCounterMetricKey key =
                new TransitionCounterMetricKey(serviceId, "User", "status", "to_ACTIVE", "PENDING", "ACTIVE");
        Instant ts = Instant.parse("2024-12-31T23:59:57Z");
        Instant aligned = CounterGranularity.S5.baseBucket().align(ts);

        service.post(key, ts, 1, "posting-2");

        assertThat(rollups.hasKey(CounterBucket.S5, aligned, key)).isTrue();
    }

    @Test
    void negativeDeltaIsApplied() {
        InMemoryPostingIdRepository postingIds = new InMemoryPostingIdRepository();
        InMemoryRollupRepository rollups = new InMemoryRollupRepository();
        TransitionCounterPostingService service =
                new TransitionCounterPostingService(postingIds, rollups, noopTransactionTemplate());

        UUID serviceId = UUID.randomUUID();
        TransitionCounterMetricKey key =
                new TransitionCounterMetricKey(serviceId, "User", "status", "to_ACTIVE", "PENDING", "ACTIVE");
        Instant ts = Instant.parse("2025-01-01T00:00:02Z");

        service.post(key, ts, -1, "posting-3");

        long count = rollups.getCount(CounterBucket.S5, ts, key);
        assertThat(count).isEqualTo(-1);
    }

    @Test
    void concurrentDuplicatePostingIsIdempotent() throws Exception {
        ThreadSafePostingIdRepository postingIds = new ThreadSafePostingIdRepository();
        InMemoryRollupRepository rollups = new InMemoryRollupRepository();
        TransitionCounterPostingService service =
                new TransitionCounterPostingService(postingIds, rollups, noopTransactionTemplate());

        UUID serviceId = UUID.randomUUID();
        TransitionCounterMetricKey key =
                new TransitionCounterMetricKey(serviceId, "User", "status", "to_ACTIVE", "PENDING", "ACTIVE");
        Instant ts = Instant.parse("2025-01-01T00:00:02Z");

        Runnable task = () -> service.post(key, ts, 1, "posting-4");
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            executor.submit(task);
            executor.submit(task);
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        long count = rollups.getCount(CounterBucket.S5, ts, key);
        assertThat(count).isEqualTo(1);
    }

    private static final class InMemoryPostingIdRepository implements TransitionCounterPostingIdRepository {
        private final Set<String> seen = new LinkedHashSet<>();

        @Override
        public List<TransitionCounterPosting> filterNew(List<TransitionCounterPosting> postings) {
            if (postings == null || postings.isEmpty()) {
                return List.of();
            }
            List<TransitionCounterPosting> accepted = new java.util.ArrayList<>();
            for (TransitionCounterPosting posting : postings) {
                if (seen.add(posting.postingId())) {
                    accepted.add(posting);
                }
            }
            return accepted.isEmpty() ? List.of() : List.copyOf(accepted);
        }
    }

    private static final class ThreadSafePostingIdRepository implements TransitionCounterPostingIdRepository {
        private final Set<String> seen =
                java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

        @Override
        public List<TransitionCounterPosting> filterNew(List<TransitionCounterPosting> postings) {
            if (postings == null || postings.isEmpty()) {
                return List.of();
            }
            List<TransitionCounterPosting> accepted = new java.util.ArrayList<>();
            for (TransitionCounterPosting posting : postings) {
                if (seen.add(posting.postingId())) {
                    accepted.add(posting);
                }
            }
            return accepted.isEmpty() ? List.of() : List.copyOf(accepted);
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

    private static final class InMemoryRollupRepository implements TransitionCounterRollupRepository {
        private final Map<RollupKey, Long> counts = new LinkedHashMap<>();

        @Override
        public void applyBatch(CounterBucket bucket, List<RollupRow> rows) {
            for (RollupRow row : rows) {
                RollupKey key = new RollupKey(bucket.label(), row.timestamp(), row);
                counts.merge(key, row.delta(), Long::sum);
            }
        }

        long getCount(CounterBucket bucket, Instant ts, TransitionCounterMetricKey key) {
            Instant aligned = bucket.align(ts);
            RollupKey lookup = new RollupKey(
                    bucket.label(),
                    aligned,
                    new RollupRow(
                            aligned,
                            key.serviceId(),
                            key.objectType(),
                            key.attribute(),
                            key.counterName(),
                            key.fromState(),
                            key.storageToState(),
                            0));
            return counts.getOrDefault(lookup, 0L);
        }

        boolean hasKey(CounterBucket bucket, Instant aligned, TransitionCounterMetricKey key) {
            RollupKey lookup = new RollupKey(
                    bucket.label(),
                    aligned,
                    new RollupRow(
                            aligned,
                            key.serviceId(),
                            key.objectType(),
                            key.attribute(),
                            key.counterName(),
                            key.fromState(),
                            key.storageToState(),
                            0));
            return counts.containsKey(lookup);
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
}

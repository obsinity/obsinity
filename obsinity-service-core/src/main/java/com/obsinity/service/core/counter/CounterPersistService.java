package com.obsinity.service.core.counter;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class CounterPersistService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void persistBatch(CounterGranularity baseGranularity, List<BatchItem> batch) {
        if (batch.isEmpty()) {
            return;
        }
        EnumSet<CounterBucket> buckets = baseGranularity.materialisedBuckets();
        // Process from smallest to largest to reduce lock contention on coarse buckets.
        List<CounterBucket> orderedBuckets = buckets.stream()
                .sorted(Comparator.comparing(CounterBucket::duration))
                .toList();
        for (CounterBucket bucket : orderedBuckets) {
            List<BatchItem> aligned = alignBatch(batch, bucket);
            if (aligned.isEmpty()) {
                continue;
            }
            String sql =
                    """
                    INSERT INTO obsinity.event_counts (ts, bucket, counter_config_id, event_type_id, key_hash, key_data, counter)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (ts, bucket, counter_config_id, key_hash)
                    DO UPDATE SET counter = obsinity.event_counts.counter + EXCLUDED.counter
                    """;
            try {
                txTemplate.execute(status -> {
                    executeBatchWithRetry(sql, aligned, bucket);
                    return null;
                });
            } catch (Exception ex) {
                log.error("Failed to persist counter rollup bucket {}", bucket.label(), ex);
                throw ex;
            }
        }
    }

    private List<BatchItem> alignBatch(List<BatchItem> batch, CounterBucket bucket) {
        List<BatchItem> aligned = new ArrayList<>(batch.size());
        for (BatchItem item : batch) {
            Instant ts = bucket.align(item.timestamp());
            aligned.add(new BatchItem(
                    ts,
                    item.counterConfigId(),
                    item.eventTypeId(),
                    item.keyHash(),
                    item.keyData(),
                    item.keyDataJson(),
                    item.delta()));
        }
        return aligned;
    }

    private void executeBatchWithRetry(String sql, List<BatchItem> aligned, CounterBucket bucket) {
        // Stable ordering reduces lock-order deadlocks across workers.
        aligned.sort(Comparator.comparing(BatchItem::counterConfigId).thenComparing(BatchItem::keyHash));
        int attempts = 0;
        int maxAttempts = 6;
        while (true) {
            attempts++;
            try {
                jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                        BatchItem item = aligned.get(i);
                        ps.setTimestamp(1, Timestamp.from(item.timestamp()));
                        ps.setString(2, bucket.label());
                        ps.setObject(3, item.counterConfigId());
                        ps.setObject(4, item.eventTypeId());
                        ps.setString(5, item.keyHash());
                        ps.setString(6, item.keyDataJson());
                        ps.setLong(7, item.delta());
                    }

                    @Override
                    public int getBatchSize() {
                        return aligned.size();
                    }
                });
                return;
            } catch (DataAccessException ex) {
                if (attempts >= maxAttempts || !isDeadlock(ex)) {
                    throw ex;
                }
                long base = 50L << Math.min(attempts, 6); // capped exponential
                long jitter = ThreadLocalRandom.current().nextLong(base, base * 2);
                long backoffMs = Math.min(jitter, 5_000L); // cap to 5s
                log.warn(
                        "Deadlock detected while persisting counter bucket {}. Retrying attempt {}/{} after {} ms",
                        bucket.label(),
                        attempts + 1,
                        maxAttempts,
                        backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private boolean isDeadlock(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sqlEx && "40P01".equals(sqlEx.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    public record BatchItem(
            Instant timestamp,
            UUID counterConfigId,
            UUID eventTypeId,
            String keyHash,
            Map<String, String> keyData,
            String keyDataJson,
            long delta) {}
}

package com.obsinity.service.core.state.transition;

import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.CounterGranularity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StateTransitionPersistService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void persistBatch(CounterGranularity baseGranularity, List<BatchItem> batch) {
        if (batch.isEmpty()) {
            return;
        }
        EnumSet<CounterBucket> buckets = baseGranularity.materialisedBuckets();
        for (CounterBucket bucket : buckets) {
            List<BatchItem> aligned = alignBatch(batch, bucket);
            if (aligned.isEmpty()) {
                continue;
            }
            String sql =
                    """
                INSERT INTO obsinity.object_state_transitions(
                    ts, bucket, service_id, object_type, attribute, from_state, to_state, transition_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (ts, bucket, service_id, object_type, attribute, from_state, to_state)
                DO UPDATE SET transition_count = obsinity.object_state_transitions.transition_count + EXCLUDED.transition_count
                """;
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    BatchItem item = aligned.get(i);
                    ps.setTimestamp(1, Timestamp.from(item.timestamp()));
                    ps.setString(2, bucket.label());
                    ps.setObject(3, item.serviceId());
                    ps.setString(4, item.objectType());
                    ps.setString(5, item.attribute());
                    ps.setString(6, item.fromState());
                    ps.setString(7, item.toState());
                    ps.setLong(8, item.count());
                }

                @Override
                public int getBatchSize() {
                    return aligned.size();
                }
            });
        }
    }

    private List<BatchItem> alignBatch(List<BatchItem> batch, CounterBucket bucket) {
        List<BatchItem> aligned = new ArrayList<>(batch.size());
        for (BatchItem item : batch) {
            Instant ts = bucket.align(item.timestamp());
            aligned.add(new BatchItem(
                    ts,
                    item.serviceId(),
                    item.objectType(),
                    item.attribute(),
                    item.fromState(),
                    item.toState(),
                    item.count()));
        }
        return aligned;
    }

    public record BatchItem(
            Instant timestamp,
            UUID serviceId,
            String objectType,
            String attribute,
            String fromState,
            String toState,
            long count) {}
}

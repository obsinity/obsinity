package com.obsinity.service.core.counter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
public class CounterPersistService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;

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
                    INSERT INTO obsinity.event_counts (ts, bucket, counter_config_id, event_type_id, key_hash, key_data, counter)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (ts, bucket, counter_config_id, key_hash)
                    DO UPDATE SET counter = obsinity.event_counts.counter + EXCLUDED.counter
                    """;
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                    BatchItem item = aligned.get(i);
                    ps.setTimestamp(1, Timestamp.from(item.timestamp()));
                    ps.setString(2, bucket.label());
                    ps.setObject(3, item.counterConfigId());
                    ps.setObject(4, item.eventTypeId());
                    ps.setString(5, item.keyHash());
                    ps.setString(6, canonicalJson(item.keyData()));
                    ps.setLong(7, item.delta());
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
                    ts, item.counterConfigId(), item.eventTypeId(), item.keyHash(), item.keyData(), item.delta()));
        }
        return aligned;
    }

    private String canonicalJson(Map<String, String> map) {
        ObjectNode node = mapper.createObjectNode();
        map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> node.put(entry.getKey(), entry.getValue()));
        return node.toString();
    }

    public record BatchItem(
            Instant timestamp,
            UUID counterConfigId,
            UUID eventTypeId,
            String keyHash,
            Map<String, String> keyData,
            long delta) {}
}

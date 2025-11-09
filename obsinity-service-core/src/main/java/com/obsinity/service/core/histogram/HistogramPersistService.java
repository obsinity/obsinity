package com.obsinity.service.core.histogram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.obsinity.service.core.config.HistogramSpec;
import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.CounterGranularity;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistogramPersistService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;

    private static final String INSERT_SQL =
            """
        INSERT INTO obsinity.event_histograms (
            ts, bucket, histogram_config_id, event_type_id, key_hash, key_data,
            sketch_cfg, sketch_payload, sample_count, sample_sum
        )
        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
        ON CONFLICT (ts, bucket, histogram_config_id, key_hash) DO NOTHING
        """;

    public void persist(
            CounterGranularity granularity,
            long epochSeconds,
            Collection<HistogramBuffer.BufferedHistogramEntry> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        CounterBucket bucket = granularity.baseBucket();
        Instant timestamp = Instant.ofEpochSecond(epochSeconds);
        List<HistogramBuffer.BufferedHistogramEntry> entries =
                batch.stream().filter(entry -> entry.getSamples() > 0).toList();
        if (entries.isEmpty()) {
            return;
        }

        List<HistogramBuffer.BufferedHistogramEntry> mutable = new ArrayList<>(entries);
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                HistogramBuffer.BufferedHistogramEntry entry = mutable.get(i);
                ps.setTimestamp(1, Timestamp.from(timestamp));
                ps.setString(2, bucket.label());
                ps.setObject(3, entry.getHistogramConfigId());
                ps.setObject(4, entry.getEventTypeId());
                ps.setString(5, entry.getKeyHash());
                ps.setString(6, canonicalJson(entry.getKeyData()));
                ps.setString(7, sketchSpecJson(entry.getSketchSpec()));
                ps.setBytes(8, HistogramSketchCodec.serialize(entry.getSketch()));
                ps.setLong(9, entry.getSamples());
                ps.setDouble(10, entry.getSum());
            }

            @Override
            public int getBatchSize() {
                return mutable.size();
            }
        });
    }

    private String canonicalJson(Map<String, String> data) {
        ObjectNode node = mapper.createObjectNode();
        data.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> node.put(entry.getKey(), entry.getValue()));
        return node.toString();
    }

    private String sketchSpecJson(HistogramSpec.SketchSpec spec) {
        ObjectNode node = mapper.createObjectNode();
        node.put("kind", spec.kind());
        node.put("relativeAccuracy", spec.relativeAccuracy());
        node.put("minValue", spec.minValue());
        node.put("maxValue", spec.maxValue());
        return node.toString();
    }
}

package com.obsinity.service.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.obsinity.service.core.config.HistogramSpec;
import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.CounterGranularity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            sketch_cfg, sketch_payload, sample_count, sample_sum, overflow_low, overflow_high
        )
        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
        ON CONFLICT (ts, bucket, histogram_config_id, key_hash) DO NOTHING
        """;

    private static final String SELECT_EXISTING_SQL =
            """
        SELECT sketch_payload, sample_count, sample_sum, overflow_low, overflow_high
        FROM obsinity.event_histograms
        WHERE ts = ? AND bucket = ? AND histogram_config_id = ? AND key_hash = ?
        FOR UPDATE
        """;

    private static final String UPDATE_SQL =
            """
        UPDATE obsinity.event_histograms
        SET sketch_cfg = ?::jsonb,
            sketch_payload = ?,
            sample_count = ?,
            sample_sum = ?,
            overflow_low = ?,
            overflow_high = ?
        WHERE ts = ? AND bucket = ? AND histogram_config_id = ? AND key_hash = ?
        """;

    @Transactional
    public void persist(
            CounterGranularity granularity,
            long epochSeconds,
            Collection<HistogramBuffer.BufferedHistogramEntry> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        Instant baseInstant = Instant.ofEpochSecond(epochSeconds);
        List<HistogramBuffer.BufferedHistogramEntry> entries =
                batch.stream().filter(entry -> entry.getSamples() > 0).toList();
        if (entries.isEmpty()) {
            return;
        }

        for (CounterBucket bucket : granularity.materialisedBuckets()) {
            Instant timestamp = bucket.align(baseInstant);
            persistBucket(bucket, timestamp, entries);
        }
    }

    private void persistBucket(
            CounterBucket bucket, Instant timestamp, List<HistogramBuffer.BufferedHistogramEntry> entries) {
        for (HistogramBuffer.BufferedHistogramEntry entry : entries) {
            if (entry.getSamples() <= 0) {
                continue;
            }
            boolean inserted = tryInsert(bucket, timestamp, entry);
            if (!inserted) {
                mergeAndUpdate(bucket, timestamp, entry);
            }
        }
    }

    private boolean tryInsert(CounterBucket bucket, Instant timestamp, HistogramBuffer.BufferedHistogramEntry entry) {
        int rows = jdbcTemplate.update(
                INSERT_SQL,
                Timestamp.from(timestamp),
                bucket.label(),
                entry.getHistogramConfigId(),
                entry.getEventTypeId(),
                entry.getKeyHash(),
                canonicalJson(entry.getKeyData()),
                sketchSpecJson(entry.getSketchSpec()),
                HistogramSketchCodec.serialize(entry.getSketch()),
                entry.getSamples(),
                entry.getSum(),
                entry.getOverflowLow(),
                entry.getOverflowHigh());
        return rows == 1;
    }

    private void mergeAndUpdate(CounterBucket bucket, Instant timestamp, HistogramBuffer.BufferedHistogramEntry entry) {
        ExistingRow existing = jdbcTemplate.query(
                SELECT_EXISTING_SQL,
                rs -> rs.next()
                        ? new ExistingRow(
                                rs.getBytes("sketch_payload"),
                                rs.getLong("sample_count"),
                                rs.getDouble("sample_sum"),
                                rs.getLong("overflow_low"),
                                rs.getLong("overflow_high"))
                        : null,
                Timestamp.from(timestamp),
                bucket.label(),
                entry.getHistogramConfigId(),
                entry.getKeyHash());

        DDSketch mergedSketch = HistogramSketchCodec.deserialize(existing != null ? existing.sketchPayload() : null);
        if (mergedSketch == null) {
            mergedSketch = HistogramSketchCodec.deserialize(HistogramSketchCodec.serialize(entry.getSketch()));
        } else {
            mergedSketch.mergeWith(entry.getSketch());
        }

        long mergedSamples = entry.getSamples() + (existing != null ? existing.sampleCount() : 0L);
        double mergedSum = entry.getSum() + (existing != null ? existing.sampleSum() : 0.0d);
        long mergedOverflowLow = entry.getOverflowLow() + (existing != null ? existing.overflowLow() : 0L);
        long mergedOverflowHigh = entry.getOverflowHigh() + (existing != null ? existing.overflowHigh() : 0L);

        jdbcTemplate.update(
                UPDATE_SQL,
                sketchSpecJson(entry.getSketchSpec()),
                HistogramSketchCodec.serialize(mergedSketch),
                mergedSamples,
                mergedSum,
                mergedOverflowLow,
                mergedOverflowHigh,
                Timestamp.from(timestamp),
                bucket.label(),
                entry.getHistogramConfigId(),
                entry.getKeyHash());
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

    private record ExistingRow(byte[] sketchPayload, long sampleCount, double sampleSum, long overflowLow, long overflowHigh) {}
}

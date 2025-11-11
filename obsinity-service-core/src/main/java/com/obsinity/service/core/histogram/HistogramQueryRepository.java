package com.obsinity.service.core.histogram;

import com.obsinity.service.core.counter.CounterBucket;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class HistogramQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<Row> fetchRange(
            UUID histogramConfigId, CounterBucket bucket, String[] hashes, Instant from, Instant to) {
        if (hashes == null || hashes.length == 0) {
            return List.of();
        }
        String sql =
                """
                SELECT key_hash, sketch_payload, sample_count, sample_sum
                FROM obsinity.event_histograms
                WHERE histogram_config_id = :histogramConfigId
                  AND bucket = :bucket
                  AND key_hash = ANY(:hashes)
                  AND ts >= :fromInclusive
                  AND ts < :toExclusive
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("histogramConfigId", histogramConfigId)
                .addValue("bucket", bucket.label())
                .addValue("hashes", hashes)
                .addValue("fromInclusive", Timestamp.from(from))
                .addValue("toExclusive", Timestamp.from(to));
        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new Row(
                        rs.getString("key_hash"),
                        rs.getBytes("sketch_payload"),
                        rs.getLong("sample_count"),
                        rs.getDouble("sample_sum")));
    }

    public record Row(String keyHash, byte[] sketchPayload, long sampleCount, double sampleSum) {}

    public Instant findEarliestTimestamp(UUID histogramConfigId) {
        String sql = """
                SELECT MIN(ts) AS earliest
                FROM obsinity.event_histograms
                WHERE histogram_config_id = :histogramConfigId
                """;
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("histogramConfigId", histogramConfigId);
        return jdbcTemplate.query(sql, params, rs -> {
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp("earliest");
                return ts != null ? ts.toInstant() : null;
            }
            return null;
        });
    }
}

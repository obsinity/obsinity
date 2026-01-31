package com.obsinity.service.core.counter;

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
public class CounterQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<KeyTotal> fetchRange(
            UUID counterConfigId, CounterBucket bucket, String[] hashes, Instant from, Instant to) {
        if (hashes == null || hashes.length == 0) {
            return List.of();
        }
        String sql =
                """
                SELECT key_hash, CAST(SUM(counter) AS BIGINT) AS total
                FROM obsinity.event_counts
                WHERE counter_config_id = :counterConfigId
                  AND bucket = :bucket
                  AND key_hash = ANY(:hashes)
                  AND ts >= :fromInclusive
                  AND ts < :toExclusive
                GROUP BY key_hash
                ORDER BY key_hash
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("counterConfigId", counterConfigId)
                .addValue("bucket", bucket.label())
                .addValue("hashes", hashes)
                .addValue("fromInclusive", Timestamp.from(from))
                .addValue("toExclusive", Timestamp.from(to));
        return jdbcTemplate.query(
                sql, params, (rs, rowNum) -> new KeyTotal(rs.getString("key_hash"), rs.getLong("total")));
    }

    public record KeyTotal(String keyHash, long total) {}

    public Instant findEarliestTimestamp(UUID counterConfigId, CounterBucket bucket) {
        String sql =
                """
                SELECT MIN(ts) AS earliest
                FROM obsinity.event_counts
                WHERE counter_config_id = :counterConfigId
                  AND bucket = :bucket
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("counterConfigId", counterConfigId)
                .addValue("bucket", bucket.label());
        return jdbcTemplate.query(sql, params, rs -> {
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp("earliest");
                return ts != null ? ts.toInstant() : null;
            }
            return null;
        });
    }

    public Instant findLatestTimestamp(UUID counterConfigId, CounterBucket bucket) {
        String sql =
                """
                SELECT MAX(ts) AS latest
                FROM obsinity.event_counts
                WHERE counter_config_id = :counterConfigId
                  AND bucket = :bucket
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("counterConfigId", counterConfigId)
                .addValue("bucket", bucket.label());
        return jdbcTemplate.query(sql, params, rs -> {
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp("latest");
                return ts != null ? ts.toInstant() : null;
            }
            return null;
        });
    }
}

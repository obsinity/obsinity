package com.obsinity.service.core.state.query;

import com.obsinity.service.core.counter.CounterBucket;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StateCountTimeseriesQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StateCountTimeseriesQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> fetchRowsInRange(
            UUID serviceId,
            String objectType,
            String attribute,
            List<String> states,
            CounterBucket bucket,
            Instant startInclusive,
            Instant endExclusive) {
        MapSqlParameterSource params = baseParams(serviceId, objectType, attribute)
                .addValue("start", java.sql.Timestamp.from(startInclusive))
                .addValue("end", java.sql.Timestamp.from(endExclusive))
                .addValue("bucket", bucket.label());
        StringBuilder sql = new StringBuilder(
                """
                SELECT ts, state_value, state_count
                  FROM obsinity.object_state_count_timeseries
                 WHERE bucket = :bucket
                   AND ts >= :start
                   AND ts < :end
                   AND service_id = :service_id
                   AND object_type = :object_type
                   AND attribute = :attribute
                """);
        if (states != null && !states.isEmpty()) {
            sql.append(" AND state_value IN (:states)");
            params.addValue("states", states);
        }
        sql.append(" ORDER BY ts ASC, state_value ASC");
        return jdbc.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new Row(
                        rs.getTimestamp("ts").toInstant(), rs.getString("state_value"), rs.getLong("state_count")));
    }

    public Instant findEarliestTimestamp(UUID serviceId, String objectType, String attribute, CounterBucket bucket) {
        MapSqlParameterSource params =
                baseParams(serviceId, objectType, attribute).addValue("bucket", bucket.label());
        return jdbc
                .query(
                        """
                SELECT MIN(ts)
                  FROM obsinity.object_state_count_timeseries
                 WHERE service_id = :service_id
                   AND object_type = :object_type
                   AND attribute = :attribute
                   AND bucket = :bucket
                """,
                        params,
                        (rs, rowNum) ->
                                rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null)
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    public Instant findLatestTimestamp(UUID serviceId, String objectType, String attribute, CounterBucket bucket) {
        MapSqlParameterSource params =
                baseParams(serviceId, objectType, attribute).addValue("bucket", bucket.label());
        return jdbc
                .query(
                        """
                SELECT MAX(ts)
                  FROM obsinity.object_state_count_timeseries
                 WHERE service_id = :service_id
                   AND object_type = :object_type
                   AND attribute = :attribute
                   AND bucket = :bucket
                """,
                        params,
                        (rs, rowNum) ->
                                rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null)
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    public Instant findEarliestTimestampInRange(
            UUID serviceId,
            String objectType,
            String attribute,
            List<String> states,
            CounterBucket bucket,
            Instant startInclusive,
            Instant endExclusive) {
        MapSqlParameterSource params = baseParams(serviceId, objectType, attribute)
                .addValue("bucket", bucket.label())
                .addValue("start", java.sql.Timestamp.from(startInclusive))
                .addValue("end", java.sql.Timestamp.from(endExclusive));
        StringBuilder sql = new StringBuilder(
                """
                SELECT MIN(ts)
                  FROM obsinity.object_state_count_timeseries
                 WHERE service_id = :service_id
                   AND object_type = :object_type
                   AND attribute = :attribute
                   AND bucket = :bucket
                   AND ts >= :start
                   AND ts < :end
                """);
        if (states != null && !states.isEmpty()) {
            sql.append(" AND state_value IN (:states)");
            params.addValue("states", states);
        }
        return jdbc
                .query(
                        sql.toString(),
                        params,
                        (rs, rowNum) ->
                                rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null)
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private MapSqlParameterSource baseParams(UUID serviceId, String objectType, String attribute) {
        return new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute);
    }

    public record Row(Instant ts, String stateValue, long count) {}

    public Map<String, Long> fetchLatestCountsInRange(
            UUID serviceId,
            String objectType,
            String attribute,
            List<String> states,
            CounterBucket bucket,
            Instant startInclusive,
            Instant endExclusive) {
        MapSqlParameterSource params = baseParams(serviceId, objectType, attribute)
                .addValue("bucket", bucket.label())
                .addValue("start", java.sql.Timestamp.from(startInclusive))
                .addValue("end", java.sql.Timestamp.from(endExclusive));
        StringBuilder sql = new StringBuilder(
                """
                WITH latest_ts AS (
                    SELECT MAX(ts) AS ts
                      FROM obsinity.object_state_count_timeseries
                     WHERE service_id = :service_id
                       AND object_type = :object_type
                       AND attribute = :attribute
                       AND bucket = :bucket
                       AND ts >= :start
                       AND ts < :end
                )
                SELECT state_value, state_count
                  FROM obsinity.object_state_count_timeseries
                 WHERE service_id = :service_id
                   AND object_type = :object_type
                   AND attribute = :attribute
                   AND bucket = :bucket
                   AND ts = (SELECT ts FROM latest_ts)
                """);
        if (states != null && !states.isEmpty()) {
            sql.append(" AND state_value IN (:states)");
            params.addValue("states", states);
        }
        sql.append(" ORDER BY state_value ASC");

        return jdbc
                .query(
                        sql.toString(),
                        params,
                        (rs, rowNum) -> Map.entry(rs.getString("state_value"), rs.getLong("state_count")))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (left, right) -> right, LinkedHashMap::new));
    }
}

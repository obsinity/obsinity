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
public class StateTransitionQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StateTransitionQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> fetchRange(UUID serviceId, CounterBucket bucket, Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("bucket", bucket.label())
                .addValue("start", java.sql.Timestamp.from(from))
                .addValue("end", java.sql.Timestamp.from(to));

        return jdbc.query(
                """
            SELECT from_state, to_state, SUM(transition_count) AS total
            FROM obsinity.object_state_transitions
            WHERE service_id = :service_id
              AND bucket = :bucket
              AND ts >= :start AND ts < :end
            GROUP BY from_state, to_state
            """,
                params,
                (rs, rowNum) -> new Row(rs.getString("from_state"), rs.getString("to_state"), rs.getLong("total")));
    }

    public Instant findEarliestTimestamp(UUID serviceId, CounterBucket bucket) {
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("service_id", serviceId).addValue("bucket", bucket.label());
        return jdbc.queryForObject(
                """
                SELECT MIN(ts) FROM obsinity.object_state_transitions
                WHERE service_id = :service_id AND bucket = :bucket
                """,
                params,
                (rs, rowNum) -> rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null);
    }

    public Instant findLatestTimestamp(UUID serviceId, CounterBucket bucket) {
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("service_id", serviceId).addValue("bucket", bucket.label());
        return jdbc.queryForObject(
                """
                SELECT MAX(ts) FROM obsinity.object_state_transitions
                WHERE service_id = :service_id AND bucket = :bucket
                """,
                params,
                (rs, rowNum) -> rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null);
    }

    public Instant findLatestTimestampInRange(
            UUID serviceId, String objectType, String attribute, CounterBucket bucket, Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute)
                .addValue("bucket", bucket.label())
                .addValue("start", java.sql.Timestamp.from(from))
                .addValue("end", java.sql.Timestamp.from(to));
        return jdbc.queryForObject(
                """
                SELECT MAX(ts) FROM obsinity.object_state_transitions
                WHERE service_id = :service_id
                  AND object_type = :object_type
                  AND attribute = :attribute
                  AND bucket = :bucket
                  AND ts >= :start
                  AND ts < :end
                """,
                params,
                (rs, rowNum) -> rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null);
    }

    public record Row(String fromState, String toState, long total) {}

    public record TransitionKey(String fromState, String toState) {}

    public Map<TransitionKey, Long> sumTransitions(
            UUID serviceId,
            String objectType,
            String attribute,
            CounterBucket bucket,
            Instant from,
            Instant to,
            List<TransitionKey> transitions) {
        if (transitions == null || transitions.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute)
                .addValue("bucket", bucket.label())
                .addValue("start", java.sql.Timestamp.from(from))
                .addValue("end", java.sql.Timestamp.from(to));

        StringBuilder transitionPredicate = new StringBuilder();
        for (int i = 0; i < transitions.size(); i++) {
            TransitionKey key = transitions.get(i);
            if (i > 0) {
                transitionPredicate.append(" OR ");
            }
            String fromParam = "from_state_" + i;
            String toParam = "to_state_" + i;
            transitionPredicate
                    .append("(from_state = :")
                    .append(fromParam)
                    .append(" AND to_state = :")
                    .append(toParam)
                    .append(")");
            params.addValue(fromParam, key.fromState());
            params.addValue(toParam, key.toState());
        }

        String sql =
                """
                SELECT from_state, to_state, SUM(transition_count) AS total
                FROM obsinity.object_state_transitions
                WHERE service_id = :service_id
                  AND object_type = :object_type
                  AND attribute = :attribute
                  AND bucket = :bucket
                  AND ts >= :start AND ts < :end
                  AND ("""
                        + transitionPredicate
                        + """
                  )
                GROUP BY from_state, to_state
                """;

        return jdbc
                .query(
                        sql,
                        params,
                        (rs, rowNum) -> Map.entry(
                                new TransitionKey(rs.getString("from_state"), rs.getString("to_state")),
                                rs.getLong("total")))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (left, right) -> right, LinkedHashMap::new));
    }
}

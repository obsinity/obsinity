package com.obsinity.service.core.state.query;

import com.obsinity.service.core.counter.CounterBucket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StateCountTimeseriesQueryRepository {

    private static final CounterBucket SNAPSHOT_BUCKET = CounterBucket.M1;

    private final NamedParameterJdbcTemplate jdbc;

    public StateCountTimeseriesQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> fetchWindow(
            UUID serviceId, String objectType, String attribute, List<String> states, Instant timestamp) {
        MapSqlParameterSource params = baseParams(serviceId, objectType, attribute)
                .addValue("ts", java.sql.Timestamp.from(timestamp))
                .addValue("bucket", SNAPSHOT_BUCKET.label());
        StringBuilder sql = new StringBuilder(
                """
                SELECT state_value, state_count
                  FROM obsinity.object_state_count_timeseries
                 WHERE bucket = :bucket
                   AND ts = :ts
                   AND service_id = :service_id
                   AND object_type = :object_type
                   AND attribute = :attribute
                """);
        if (states != null && !states.isEmpty()) {
            sql.append(" AND state_value IN (:states)");
            params.addValue("states", states);
        }
        sql.append(" ORDER BY state_value ASC");
        return jdbc.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new Row(rs.getString("state_value"), rs.getLong("state_count")));
    }

    public Instant findEarliestTimestamp(UUID serviceId, String objectType, String attribute) {
        MapSqlParameterSource params = baseParams(serviceId, objectType, attribute)
                .addValue("bucket", SNAPSHOT_BUCKET.label());
        return jdbc.query(
                        """
                SELECT MIN(ts)
                  FROM obsinity.object_state_count_timeseries
                 WHERE service_id = :service_id
                   AND object_type = :object_type
                   AND attribute = :attribute
                   AND bucket = :bucket
                """,
                        params,
                        (rs, rowNum) -> rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private MapSqlParameterSource baseParams(UUID serviceId, String objectType, String attribute) {
        return new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute);
    }

    public record Row(String stateValue, long count) {}
}

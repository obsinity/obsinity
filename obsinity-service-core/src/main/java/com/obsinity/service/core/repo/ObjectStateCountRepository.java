package com.obsinity.service.core.repo;

import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ObjectStateCountRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ObjectStateCountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void increment(UUID serviceId, String objectType, String attribute, String stateValue) {
        updateCount(serviceId, objectType, attribute, stateValue, 1);
    }

    public void decrement(UUID serviceId, String objectType, String attribute, String stateValue) {
        updateCount(serviceId, objectType, attribute, stateValue, -1);
    }

    public java.util.List<StateCountRow> list(
            UUID serviceId, String objectType, String attribute, java.util.List<String> states, int offset, int limit) {
        if (serviceId == null || objectType == null || attribute == null) {
            return java.util.List.of();
        }
        var params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute)
                .addValue("offset", Math.max(0, offset))
                .addValue("limit", Math.max(1, limit));
        StringBuilder sql = new StringBuilder(
                """
                select state_value, count
                  from obsinity.object_state_counts
                 where service_id = :service_id
                   and object_type = :object_type
                   and attribute = :attribute
                """);
        if (states != null && !states.isEmpty()) {
            sql.append(" and state_value in (:states)");
            params.addValue("states", states);
        }
        sql.append(" order by state_value asc limit :limit offset :offset");
        return jdbc.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new StateCountRow(rs.getString("state_value"), rs.getLong("count")));
    }

    public long countStates(UUID serviceId, String objectType, String attribute, java.util.List<String> states) {
        if (serviceId == null || objectType == null || attribute == null) {
            return 0;
        }
        var params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute);
        StringBuilder sql = new StringBuilder(
                """
                select count(1)
                  from obsinity.object_state_counts
                 where service_id = :service_id
                   and object_type = :object_type
                   and attribute = :attribute
                """);
        if (states != null && !states.isEmpty()) {
            sql.append(" and state_value in (:states)");
            params.addValue("states", states);
        }
        Long total = jdbc.queryForObject(sql.toString(), params, Long.class);
        return total == null ? 0 : total;
    }

    private void updateCount(UUID serviceId, String objectType, String attribute, String stateValue, long delta) {
        if (serviceId == null || objectType == null || attribute == null || stateValue == null) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute)
                .addValue("state_value", stateValue)
                .addValue("delta", delta);

        jdbc.update(
                """
            insert into obsinity.object_state_counts(service_id, object_type, attribute, state_value, count)
            values (:service_id, :object_type, :attribute, :state_value, greatest(:delta, 0))
            on conflict (service_id, object_type, attribute, state_value)
            do update set count = greatest(0, obsinity.object_state_counts.count + :delta)
            """,
                params);
    }

    public java.util.List<StateCountSnapshot> snapshotAll() {
        return jdbc.query(
                """
                select service_id, object_type, attribute, state_value, count
                  from obsinity.object_state_counts
                """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> new StateCountSnapshot(
                        (java.util.UUID) rs.getObject("service_id"),
                        rs.getString("object_type"),
                        rs.getString("attribute"),
                        rs.getString("state_value"),
                        rs.getLong("count")));
    }

    public record StateCountRow(String state, long count) {}

    public record StateCountSnapshot(
            java.util.UUID serviceId, String objectType, String attribute, String stateValue, long count) {}
}

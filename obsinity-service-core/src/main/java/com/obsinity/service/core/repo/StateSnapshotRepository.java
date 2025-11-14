package com.obsinity.service.core.repo;

import com.obsinity.service.core.counter.CounterGranularity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StateSnapshotRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StateSnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(
            UUID serviceId,
            String objectType,
            String objectId,
            String attribute,
            String stateValue,
            Instant occurredAt) {
        if (serviceId == null || objectType == null || objectId == null || attribute == null) {
            return;
        }
        Instant timestamp = occurredAt != null ? occurredAt : Instant.now();
        Instant aligned = CounterGranularity.S5.baseBucket().align(timestamp);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ts", java.sql.Timestamp.from(aligned))
                .addValue("bucket", CounterGranularity.S5.name())
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("object_id", objectId)
                .addValue("attribute", attribute)
                .addValue("state_value", stateValue);

        jdbc.update(
                """
            insert into obsinity.object_state(ts, bucket, service_id, object_type, object_id, attribute, state_value)
            values (:ts, :bucket, :service_id, :object_type, :object_id, :attribute, :state_value)
            on conflict (ts, bucket, service_id, object_type, object_id, attribute)
            do update set state_value = excluded.state_value
            """,
                params);
    }

    public String findLatest(UUID serviceId, String objectType, String objectId, String attribute) {
        if (serviceId == null || objectType == null || objectId == null || attribute == null) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("object_id", objectId)
                .addValue("attribute", attribute)
                .addValue("bucket", CounterGranularity.S5.name());

        return jdbc.query(
                        """
                select state_value
                from obsinity.object_state
                where service_id = :service_id
                  and object_type = :object_type
                  and object_id = :object_id
                  and attribute = :attribute
                  and bucket = :bucket
                order by ts desc
                limit 1
                """,
                        params,
                        (rs, rowNum) -> rs.getString(1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public void delete(UUID serviceId, String objectType, String objectId, String attribute) {
        if (serviceId == null || objectType == null || objectId == null || attribute == null) {
            return;
        }
        jdbc.update(
                "delete from obsinity.object_state where service_id = :service_id and object_type = :object_type "
                        + "and object_id = :object_id and attribute = :attribute",
                Map.of(
                        "service_id", serviceId,
                        "object_type", objectType,
                        "object_id", objectId,
                        "attribute", attribute));
    }
}

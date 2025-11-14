package com.obsinity.service.core.repo;

import com.obsinity.service.core.counter.CounterGranularity;
import java.time.Instant;
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

    public void increment(UUID serviceId, String objectType, String attribute, String stateValue, Instant occurredAt) {
        updateCount(serviceId, objectType, attribute, stateValue, 1, occurredAt);
    }

    public void decrement(UUID serviceId, String objectType, String attribute, String stateValue, Instant occurredAt) {
        updateCount(serviceId, objectType, attribute, stateValue, -1, occurredAt);
    }

    private void updateCount(
            UUID serviceId, String objectType, String attribute, String stateValue, long delta, Instant occurredAt) {
        if (serviceId == null || objectType == null || attribute == null || stateValue == null) {
            return;
        }
        Instant timestamp = occurredAt != null ? occurredAt : Instant.now();
        Instant aligned = CounterGranularity.S5.baseBucket().align(timestamp);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ts", java.sql.Timestamp.from(aligned))
                .addValue("bucket", CounterGranularity.S5.name())
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute)
                .addValue("state_value", stateValue)
                .addValue("delta", delta);

        jdbc.update(
                """
            insert into obsinity.object_state_counts(ts, bucket, service_id, object_type, attribute, state_value, count)
            values (:ts, :bucket, :service_id, :object_type, :attribute, :state_value, greatest(:delta, 0))
            on conflict (ts, bucket, service_id, object_type, attribute, state_value)
            do update set count = greatest(0, obsinity.object_state_counts.count + :delta)
            """,
                params);
    }
}

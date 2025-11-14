package com.obsinity.service.core.repo;

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

    public void upsert(UUID serviceId, String objectType, String objectId, String attribute, String stateValue) {
        if (serviceId == null || objectType == null || objectId == null || attribute == null) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("object_id", objectId)
                .addValue("attribute", attribute)
                .addValue("state_value", stateValue)
                .addValue("updated_at", Instant.now());

        jdbc.update(
                """
            insert into obs_state_snapshots(service_id, object_type, object_id, attribute, state_value, updated_at)
            values (:service_id, :object_type, :object_id, :attribute, :state_value, :updated_at)
            on conflict (service_id, object_type, object_id, attribute)
            do update set state_value = excluded.state_value, updated_at = excluded.updated_at
            """,
                params);
    }

    public void delete(UUID serviceId, String objectType, String objectId, String attribute) {
        if (serviceId == null || objectType == null || objectId == null || attribute == null) {
            return;
        }
        jdbc.update(
                "delete from obs_state_snapshots where service_id = :service_id and object_type = :object_type "
                        + "and object_id = :object_id and attribute = :attribute",
                Map.of(
                        "service_id", serviceId,
                        "object_type", objectType,
                        "object_id", objectId,
                        "attribute", attribute));
    }
}

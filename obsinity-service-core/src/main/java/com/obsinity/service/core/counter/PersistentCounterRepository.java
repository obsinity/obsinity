package com.obsinity.service.core.counter;

import com.obsinity.service.core.impl.JsonUtil;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class PersistentCounterRepository {

    private static final String REGISTER_EVENT_SQL =
            """
            insert into obsinity.event_registry (event_id, first_seen_at)
            values (:event_id, now())
            on conflict do nothing
            """;

    private static final String UPSERT_COUNTER_SQL =
            """
            insert into obsinity.persistent_counters
              (counter_name, dimension_key, dimensions_json, value, updated_at, floor_at_zero)
            values
              (:counter_name, :dimension_key, cast(:dimensions_json as jsonb), :delta, now(), :floor_at_zero)
            on conflict (counter_name, dimension_key)
            do update set
              value = case
                when obsinity.persistent_counters.floor_at_zero
                then greatest(0, obsinity.persistent_counters.value + excluded.value)
                else obsinity.persistent_counters.value + excluded.value
              end,
              updated_at = now(),
              dimensions_json = excluded.dimensions_json,
              floor_at_zero = excluded.floor_at_zero
            """;

    private static final String DUPLICATE_EVENT_SQL =
            """
            insert into obsinity.duplicate_event_audit
              (event_id, first_seen_at, duplicate_seen_at, event_type, dimensions_json, payload_json)
            select :event_id, er.first_seen_at, now(), :event_type, cast(:dimensions_json as jsonb), cast(:payload_json as jsonb)
            from obsinity.event_registry er
            where er.event_id = :event_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public boolean applyEvent(UUID eventId, String eventType, String payloadJson, List<CounterUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return false;
        }
        int registered = jdbc.update(REGISTER_EVENT_SQL, new MapSqlParameterSource("event_id", eventId));
        if (registered == 0) {
            auditDuplicate(eventId, eventType, payloadJson, updates);
            return false;
        }
        for (CounterUpdate update : updates) {
            jdbc.update(
                    UPSERT_COUNTER_SQL,
                    new MapSqlParameterSource()
                            .addValue("counter_name", update.counterName())
                            .addValue("dimension_key", update.dimensionKey())
                            .addValue("dimensions_json", update.dimensionsJson())
                            .addValue("delta", update.delta())
                            .addValue("floor_at_zero", update.floorAtZero()));
        }
        return true;
    }

    private void auditDuplicate(UUID eventId, String eventType, String payloadJson, List<CounterUpdate> updates) {
        String dimensions = updates.isEmpty() ? null : updates.get(0).dimensionsJson();
        String payload = payloadJson != null ? payloadJson : JsonUtil.toJson(java.util.Map.of());
        int wrote = jdbc.update(
                DUPLICATE_EVENT_SQL,
                new MapSqlParameterSource()
                        .addValue("event_id", eventId)
                        .addValue("event_type", eventType)
                        .addValue("dimensions_json", dimensions)
                        .addValue("payload_json", payload));
        if (wrote == 0) {
            log.debug("Duplicate event audit skipped for event_id={}", eventId);
        }
    }

    public record CounterUpdate(
            String counterName, String dimensionKey, String dimensionsJson, long delta, boolean floorAtZero) {}
}

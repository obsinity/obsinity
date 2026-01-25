package com.obsinity.service.core.state.transition.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.state.transition.counter.TransitionCounterFootprintEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSyntheticTerminalRecordRepository implements SyntheticTerminalRecordRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcSyntheticTerminalRecordRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public boolean insertIfEligible(SyntheticTerminalRecord record, Instant expectedLastEventTs) {
        if (record == null || expectedLastEventTs == null) {
            return false;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", record.serviceId())
                .addValue("object_type", record.objectType())
                .addValue("object_id", record.objectId())
                .addValue("attribute", record.attribute())
                .addValue("rule_id", record.ruleId())
                .addValue("synthetic_event_id", record.syntheticEventId())
                .addValue("synthetic_ts", java.sql.Timestamp.from(record.syntheticTs()))
                .addValue("synthetic_state", record.syntheticState())
                .addValue("emit_service_id", record.emitServiceId())
                .addValue("reason", record.reason())
                .addValue("origin", record.origin())
                .addValue("status", record.status())
                .addValue("last_event_ts", java.sql.Timestamp.from(record.lastEventTs()))
                .addValue("last_state", record.lastState())
                .addValue("expected_last_event_ts", java.sql.Timestamp.from(expectedLastEventTs));

        int inserted = jdbc.update(
                """
            insert into obsinity.synthetic_terminal_events(
                service_id,
                object_type,
                object_id,
                attribute,
                rule_id,
                synthetic_event_id,
                synthetic_ts,
                synthetic_state,
                emit_service_id,
                reason,
                origin,
                status,
                last_event_ts,
                last_state)
            select :service_id,
                   :object_type,
                   :object_id,
                   :attribute,
                   :rule_id,
                   :synthetic_event_id,
                   :synthetic_ts,
                   :synthetic_state,
                   :emit_service_id,
                   :reason,
                   :origin,
                   :status,
                   :last_event_ts,
                   :last_state
            where exists (
                select 1
                from obsinity.object_transition_snapshots
                where service_id = :service_id
                  and object_type = :object_type
                  and object_id = :object_id
                  and attribute = :attribute
                  and terminal_state is null
                  and last_event_ts = :expected_last_event_ts
            )
            on conflict (service_id, object_type, object_id, attribute, rule_id, synthetic_ts)
            do nothing
            """,
                params);
        return inserted > 0;
    }

    @Override
    public void recordFootprint(String syntheticEventId, List<TransitionCounterFootprintEntry> entries) {
        if (syntheticEventId == null) {
            return;
        }
        String payload = "[]";
        try {
            if (entries != null) {
                payload = mapper.writeValueAsString(entries);
            }
        } catch (Exception ignore) {
        }
        jdbc.update(
                """
            update obsinity.synthetic_terminal_events
            set transition_footprint = :footprint::jsonb
            where synthetic_event_id = :synthetic_event_id
            """,
                new MapSqlParameterSource()
                        .addValue("footprint", payload)
                        .addValue("synthetic_event_id", syntheticEventId));
    }

    @Override
    public List<SyntheticTerminalRecord> findActive(
            UUID serviceId, String objectType, String objectId, String attribute) {
        if (serviceId == null
                || objectType == null
                || objectType.isBlank()
                || objectId == null
                || objectId.isBlank()
                || attribute == null
                || attribute.isBlank()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("object_id", objectId)
                .addValue("attribute", attribute);
        List<SyntheticTerminalRecord> results = new ArrayList<>();
        jdbc.query(
                """
                select service_id,
                       object_type,
                       object_id,
                       attribute,
                       rule_id,
                       synthetic_event_id,
                       synthetic_ts,
                       synthetic_state,
                       emit_service_id,
                       reason,
                       origin,
                       status,
                       last_event_ts,
                       last_state,
                       superseded_by_event_id,
                       superseded_at,
                       reversed_at,
                       transition_footprint
                from obsinity.synthetic_terminal_events
                where service_id = :service_id
                  and object_type = :object_type
                  and object_id = :object_id
                  and attribute = :attribute
                  and status = 'ACTIVE'
                order by synthetic_ts asc
                """,
                params,
                rs -> {
                    Instant syntheticTs = rs.getTimestamp("synthetic_ts") != null
                            ? rs.getTimestamp("synthetic_ts").toInstant()
                            : null;
                    Instant lastEventTs = rs.getTimestamp("last_event_ts") != null
                            ? rs.getTimestamp("last_event_ts").toInstant()
                            : null;
                    Instant supersededAt = rs.getTimestamp("superseded_at") != null
                            ? rs.getTimestamp("superseded_at").toInstant()
                            : null;
                    Instant reversedAt = rs.getTimestamp("reversed_at") != null
                            ? rs.getTimestamp("reversed_at").toInstant()
                            : null;
                    List<TransitionCounterFootprintEntry> footprint =
                            decodeFootprint(rs.getString("transition_footprint"));
                    results.add(new SyntheticTerminalRecord(
                            (UUID) rs.getObject("service_id"),
                            rs.getString("object_type"),
                            rs.getString("object_id"),
                            rs.getString("attribute"),
                            rs.getString("rule_id"),
                            rs.getString("synthetic_event_id"),
                            syntheticTs,
                            rs.getString("synthetic_state"),
                            rs.getString("emit_service_id"),
                            rs.getString("reason"),
                            rs.getString("origin"),
                            rs.getString("status"),
                            lastEventTs,
                            rs.getString("last_state"),
                            rs.getString("superseded_by_event_id"),
                            supersededAt,
                            reversedAt,
                            footprint));
                });
        return results.isEmpty() ? List.of() : List.copyOf(results);
    }

    @Override
    public boolean supersede(String syntheticEventId, String supersededByEventId, Instant supersededAt) {
        if (syntheticEventId == null || supersededByEventId == null || supersededAt == null) {
            return false;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("synthetic_event_id", syntheticEventId)
                .addValue("superseded_by_event_id", supersededByEventId)
                .addValue("superseded_at", java.sql.Timestamp.from(supersededAt));
        int updated = jdbc.update(
                """
            update obsinity.synthetic_terminal_events
            set status = 'SUPERSEDED',
                superseded_by_event_id = :superseded_by_event_id,
                superseded_at = :superseded_at
            where synthetic_event_id = :synthetic_event_id
              and status = 'ACTIVE'
            """,
                params);
        return updated > 0;
    }

    @Override
    public void markReversed(String syntheticEventId, Instant reversedAt) {
        if (syntheticEventId == null || reversedAt == null) {
            return;
        }
        jdbc.update(
                """
            update obsinity.synthetic_terminal_events
            set reversed_at = :reversed_at
            where synthetic_event_id = :synthetic_event_id
            """,
                new MapSqlParameterSource()
                        .addValue("synthetic_event_id", syntheticEventId)
                        .addValue("reversed_at", java.sql.Timestamp.from(reversedAt)));
    }

    private List<TransitionCounterFootprintEntry> decodeFootprint(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            TransitionCounterFootprintEntry[] entries =
                    mapper.readValue(payload, TransitionCounterFootprintEntry[].class);
            return entries == null || entries.length == 0 ? List.of() : List.of(entries);
        } catch (Exception ex) {
            return List.of();
        }
    }
}

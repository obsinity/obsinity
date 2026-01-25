package com.obsinity.service.core.state.transition.outcome;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransitionOutcomeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public TransitionOutcomeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordFirstSeen(
            UUID serviceId, String objectType, String objectId, String attribute, String state, Instant firstSeenTs) {
        if (serviceId == null
                || objectType == null
                || objectId == null
                || attribute == null
                || state == null
                || firstSeenTs == null) {
            return;
        }
        jdbc.update(
                """
            insert into obsinity.object_transition_state_first_seen(
                service_id,
                object_type,
                object_id,
                attribute,
                state,
                first_seen_ts)
            values (
                :service_id,
                :object_type,
                :object_id,
                :attribute,
                :state,
                :first_seen_ts
            )
            on conflict (service_id, object_type, object_id, attribute, state)
            do nothing
            """,
                new MapSqlParameterSource()
                        .addValue("service_id", serviceId)
                        .addValue("object_type", objectType)
                        .addValue("object_id", objectId)
                        .addValue("attribute", attribute)
                        .addValue("state", state)
                        .addValue("first_seen_ts", java.sql.Timestamp.from(firstSeenTs)));
    }

    public void recordSyntheticOutcome(
            UUID serviceId,
            String objectType,
            String objectId,
            String attribute,
            String terminalState,
            Instant terminalTs,
            String syntheticEventId) {
        if (serviceId == null
                || objectType == null
                || objectId == null
                || attribute == null
                || terminalState == null
                || terminalTs == null
                || syntheticEventId == null) {
            return;
        }
        jdbc.update(
                """
            insert into obsinity.object_transition_outcomes(
                service_id,
                object_type,
                object_id,
                attribute,
                terminal_state,
                terminal_ts,
                origin,
                synthetic_event_id,
                updated_at)
            values (
                :service_id,
                :object_type,
                :object_id,
                :attribute,
                :terminal_state,
                :terminal_ts,
                'SYNTHETIC',
                :synthetic_event_id,
                now()
            )
            on conflict (service_id, object_type, object_id, attribute)
            do update set
                terminal_state = excluded.terminal_state,
                terminal_ts = excluded.terminal_ts,
                origin = excluded.origin,
                synthetic_event_id = excluded.synthetic_event_id,
                updated_at = now()
            where obsinity.object_transition_outcomes.origin is null
               or obsinity.object_transition_outcomes.origin = 'SYNTHETIC'
            """,
                new MapSqlParameterSource()
                        .addValue("service_id", serviceId)
                        .addValue("object_type", objectType)
                        .addValue("object_id", objectId)
                        .addValue("attribute", attribute)
                        .addValue("terminal_state", terminalState)
                        .addValue("terminal_ts", java.sql.Timestamp.from(terminalTs))
                        .addValue("synthetic_event_id", syntheticEventId));
    }

    public void recordObservedOutcome(
            UUID serviceId,
            String objectType,
            String objectId,
            String attribute,
            String terminalState,
            Instant terminalTs,
            String supersededByEventId) {
        if (serviceId == null
                || objectType == null
                || objectId == null
                || attribute == null
                || terminalState == null
                || terminalTs == null) {
            return;
        }
        jdbc.update(
                """
            insert into obsinity.object_transition_outcomes(
                service_id,
                object_type,
                object_id,
                attribute,
                terminal_state,
                terminal_ts,
                origin,
                superseded_by_event_id,
                updated_at)
            values (
                :service_id,
                :object_type,
                :object_id,
                :attribute,
                :terminal_state,
                :terminal_ts,
                'OBSERVED',
                :superseded_by_event_id,
                now()
            )
            on conflict (service_id, object_type, object_id, attribute)
            do update set
                terminal_state = excluded.terminal_state,
                terminal_ts = excluded.terminal_ts,
                origin = excluded.origin,
                superseded_by_event_id = excluded.superseded_by_event_id,
                synthetic_event_id = coalesce(obsinity.object_transition_outcomes.synthetic_event_id,
                                              excluded.synthetic_event_id),
                updated_at = now()
            where obsinity.object_transition_outcomes.origin is null
               or obsinity.object_transition_outcomes.origin = 'SYNTHETIC'
            """,
                new MapSqlParameterSource()
                        .addValue("service_id", serviceId)
                        .addValue("object_type", objectType)
                        .addValue("object_id", objectId)
                        .addValue("attribute", attribute)
                        .addValue("terminal_state", terminalState)
                        .addValue("terminal_ts", java.sql.Timestamp.from(terminalTs))
                        .addValue("superseded_by_event_id", supersededByEventId)
                        .addValue("synthetic_event_id", null));
    }
}

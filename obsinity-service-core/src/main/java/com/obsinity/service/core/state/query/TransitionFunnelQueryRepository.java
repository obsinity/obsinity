package com.obsinity.service.core.state.query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransitionFunnelQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public TransitionFunnelQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long cohortCount(
            UUID serviceId,
            String objectType,
            String attribute,
            String entryState,
            Instant windowStart,
            Instant windowEnd) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute)
                .addValue("entry_state", entryState)
                .addValue("start", java.sql.Timestamp.from(windowStart))
                .addValue("end", java.sql.Timestamp.from(windowEnd));
        Long result = jdbc.queryForObject(
                """
            select count(*)
            from obsinity.object_transition_state_first_seen
            where service_id = :service_id
              and object_type = :object_type
              and attribute = :attribute
              and state = :entry_state
              and first_seen_ts >= :start
              and first_seen_ts < :end
            """,
                params,
                Long.class);
        return result == null ? 0L : result;
    }

    public List<OutcomeRow> outcomeBreakdownAsOf(
            UUID serviceId,
            String objectType,
            String attribute,
            String entryState,
            Instant windowStart,
            Instant windowEnd,
            Instant asOf,
            List<String> terminalStates) {
        if (terminalStates == null || terminalStates.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute)
                .addValue("entry_state", entryState)
                .addValue("start", java.sql.Timestamp.from(windowStart))
                .addValue("end", java.sql.Timestamp.from(windowEnd))
                .addValue("as_of", java.sql.Timestamp.from(asOf))
                .addValue("terminal_states", terminalStates);
        List<OutcomeRow> results = new ArrayList<>();
        jdbc.query(
                """
            with cohort as (
                select service_id, object_type, object_id, attribute, first_seen_ts
                from obsinity.object_transition_state_first_seen
                where service_id = :service_id
                  and object_type = :object_type
                  and attribute = :attribute
                  and state = :entry_state
                  and first_seen_ts >= :start
                  and first_seen_ts < :end
            )
            select o.terminal_state, o.origin, count(*) as total
            from cohort c
            join obsinity.object_transition_outcomes o
              on o.service_id = c.service_id
             and o.object_type = c.object_type
             and o.object_id = c.object_id
             and o.attribute = c.attribute
            where o.terminal_ts is not null
              and o.terminal_ts <= :as_of
              and o.terminal_state in (:terminal_states)
            group by o.terminal_state, o.origin
            """,
                params,
                (rs, rowNum) -> results.add(
                        new OutcomeRow(rs.getString("terminal_state"), rs.getString("origin"), rs.getLong("total"))));
        return results.isEmpty() ? List.of() : List.copyOf(results);
    }

    public List<OutcomeRow> outcomeBreakdownWithHorizon(
            UUID serviceId,
            String objectType,
            String attribute,
            String entryState,
            Instant windowStart,
            Instant windowEnd,
            Duration horizon,
            List<String> terminalStates) {
        if (terminalStates == null || terminalStates.isEmpty() || horizon == null) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute)
                .addValue("entry_state", entryState)
                .addValue("start", java.sql.Timestamp.from(windowStart))
                .addValue("end", java.sql.Timestamp.from(windowEnd))
                .addValue("horizon_seconds", horizon.getSeconds())
                .addValue("terminal_states", terminalStates);
        List<OutcomeRow> results = new ArrayList<>();
        jdbc.query(
                """
            with cohort as (
                select service_id, object_type, object_id, attribute, first_seen_ts
                from obsinity.object_transition_state_first_seen
                where service_id = :service_id
                  and object_type = :object_type
                  and attribute = :attribute
                  and state = :entry_state
                  and first_seen_ts >= :start
                  and first_seen_ts < :end
            )
            select o.terminal_state, o.origin, count(*) as total
            from cohort c
            join obsinity.object_transition_outcomes o
              on o.service_id = c.service_id
             and o.object_type = c.object_type
             and o.object_id = c.object_id
             and o.attribute = c.attribute
            where o.terminal_ts is not null
              and o.terminal_ts <= (c.first_seen_ts + (:horizon_seconds * interval '1 second'))
              and o.terminal_state in (:terminal_states)
            group by o.terminal_state, o.origin
            """,
                params,
                (rs, rowNum) -> results.add(
                        new OutcomeRow(rs.getString("terminal_state"), rs.getString("origin"), rs.getLong("total"))));
        return results.isEmpty() ? List.of() : List.copyOf(results);
    }

    public record OutcomeRow(String terminalState, String origin, long total) {}
}

package com.obsinity.service.core.state.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransitionOutcomeQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public TransitionOutcomeQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FirstSeenRow> fetchFirstSeenStates(
            UUID serviceId, String objectType, String objectId, String attribute) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("object_id", objectId)
                .addValue("attribute", attribute);
        List<FirstSeenRow> results = new ArrayList<>();
        jdbc.query(
                """
            select state, first_seen_ts
            from obsinity.object_transition_state_first_seen
            where service_id = :service_id
              and object_type = :object_type
              and object_id = :object_id
              and attribute = :attribute
            order by first_seen_ts asc
            """,
                params,
                (rs, rowNum) -> results.add(new FirstSeenRow(
                        rs.getString("state"), rs.getTimestamp("first_seen_ts").toInstant())));
        return results.isEmpty() ? List.of() : List.copyOf(results);
    }

    public OutcomeRow fetchOutcome(UUID serviceId, String objectType, String objectId, String attribute) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("object_id", objectId)
                .addValue("attribute", attribute);
        List<OutcomeRow> results = new ArrayList<>();
        jdbc.query(
                """
            select terminal_state, terminal_ts, origin, synthetic_event_id, superseded_by_event_id
            from obsinity.object_transition_outcomes
            where service_id = :service_id
              and object_type = :object_type
              and object_id = :object_id
              and attribute = :attribute
            """,
                params,
                (rs, rowNum) -> results.add(new OutcomeRow(
                        rs.getString("terminal_state"),
                        rs.getTimestamp("terminal_ts") != null
                                ? rs.getTimestamp("terminal_ts").toInstant()
                                : null,
                        rs.getString("origin"),
                        rs.getString("synthetic_event_id"),
                        rs.getString("superseded_by_event_id"))));
        return results.isEmpty() ? null : results.get(0);
    }

    public record FirstSeenRow(String state, Instant firstSeenTs) {}

    public record OutcomeRow(
            String terminalState,
            Instant terminalTs,
            String origin,
            String syntheticEventId,
            String supersededByEventId) {}
}

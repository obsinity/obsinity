package com.obsinity.service.core.state.transition.health;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransitionOpsHealthRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public TransitionOpsHealthRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countSyntheticByStatus(String status) {
        if (status == null || status.isBlank()) {
            return 0L;
        }
        Long result = jdbc.queryForObject(
                """
            select count(*)
            from obsinity.synthetic_terminal_events
            where status = :status
            """,
                new MapSqlParameterSource().addValue("status", status),
                Long.class);
        return result == null ? 0L : result;
    }

    public long countPostingDedupStore() {
        Long result = jdbc.queryForObject(
                "select count(*) from obsinity.transition_counter_postings", new MapSqlParameterSource(), Long.class);
        return result == null ? 0L : result;
    }
}

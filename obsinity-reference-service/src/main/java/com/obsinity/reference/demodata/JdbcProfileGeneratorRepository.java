package com.obsinity.reference.demodata;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProfileGeneratorRepository implements ProfileGeneratorRepository {

    private static final String TABLE = "demo_user_profiles";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcProfileGeneratorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countProfiles() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public int insertProfiles(List<UUID> ids, String initialState, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String sql =
                """
                INSERT INTO demo_user_profiles (id, state, state_changed_at, created_at, updated_at)
                VALUES (:id, :state, :state_changed_at, :created_at, :updated_at)
                ON CONFLICT (id) DO NOTHING
                """;
        Timestamp ts = Timestamp.from(now);
        MapSqlParameterSource[] batch = ids.stream()
                .map(id -> new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("state", initialState)
                        .addValue("state_changed_at", ts)
                        .addValue("created_at", ts)
                        .addValue("updated_at", ts))
                .toArray(MapSqlParameterSource[]::new);
        int[] results = jdbc.batchUpdate(sql, batch);
        int inserted = 0;
        for (int result : results) {
            inserted += Math.max(result, 0);
        }
        return inserted;
    }

    @Override
    public List<ProfileCandidate> selectCandidates(
            String fromState, Instant upperBound, Instant lowerBoundInclusive, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String sql;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("state", fromState)
                .addValue("upperBound", Timestamp.from(upperBound))
                .addValue("limit", limit);
        if (lowerBoundInclusive == null) {
            sql =
                    """
                    SELECT id, state_changed_at
                    FROM demo_user_profiles
                    WHERE state = :state
                      AND state_changed_at <= :upperBound
                    ORDER BY state_changed_at ASC
                    LIMIT :limit
                    """;
        } else {
            sql =
                    """
                    SELECT id, state_changed_at
                    FROM demo_user_profiles
                    WHERE state = :state
                      AND state_changed_at <= :upperBound
                      AND state_changed_at >= :lowerBound
                    ORDER BY state_changed_at ASC
                    LIMIT :limit
                    """;
            params.addValue("lowerBound", Timestamp.from(lowerBoundInclusive));
        }
        return jdbc.query(
                sql,
                params,
                (rs, rowNum) -> new ProfileCandidate(
                        (UUID) rs.getObject("id"),
                        rs.getTimestamp("state_changed_at").toInstant()));
    }

    @Override
    public int casUpdateState(UUID id, String fromState, Instant expectedStateChangedAt, String toState, Instant now) {
        String sql =
                """
                UPDATE demo_user_profiles
                SET state = :toState,
                    state_changed_at = :now,
                    updated_at = :now
                WHERE id = :id
                  AND state = :fromState
                  AND state_changed_at = :expected
                """;
        Map<String, Object> params = Map.of(
                "id", id,
                "fromState", fromState,
                "toState", toState,
                "expected", Timestamp.from(expectedStateChangedAt),
                "now", Timestamp.from(now));
        return jdbc.update(sql, params);
    }
}

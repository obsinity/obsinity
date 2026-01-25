package com.obsinity.service.core.state.transition.counter;

import com.obsinity.service.core.state.transition.telemetry.TransitionTelemetry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTransitionCounterPostingIdRepository implements TransitionCounterPostingIdRepository {
    private final JdbcTemplate jdbcTemplate;
    private final TransitionTelemetry telemetry;

    public JdbcTransitionCounterPostingIdRepository(JdbcTemplate jdbcTemplate, TransitionTelemetry telemetry) {
        this.jdbcTemplate = jdbcTemplate;
        this.telemetry = telemetry;
    }

    @Override
    public List<TransitionCounterPosting> filterNew(List<TransitionCounterPosting> postings) {
        if (postings == null || postings.isEmpty()) {
            return List.of();
        }
        String sql = "insert into obsinity.transition_counter_postings(posting_id) values (?) on conflict do nothing";
        int[] counts = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setString(1, postings.get(i).postingId());
            }

            @Override
            public int getBatchSize() {
                return postings.size();
            }
        });
        List<TransitionCounterPosting> accepted = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                accepted.add(postings.get(i));
            }
        }
        if (telemetry != null) {
            long hits = postings.size() - accepted.size();
            telemetry.recordPostingDedupHits(hits);
        }
        return accepted.isEmpty() ? List.of() : List.copyOf(accepted);
    }
}

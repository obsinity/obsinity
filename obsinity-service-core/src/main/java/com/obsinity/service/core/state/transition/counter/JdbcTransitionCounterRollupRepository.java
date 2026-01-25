package com.obsinity.service.core.state.transition.counter;

import com.obsinity.service.core.counter.CounterBucket;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
public class JdbcTransitionCounterRollupRepository implements TransitionCounterRollupRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTransitionCounterRollupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void applyBatch(CounterBucket bucket, List<RollupRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        rows.sort(Comparator.comparing(RollupRow::serviceId)
                .thenComparing(RollupRow::objectType)
                .thenComparing(RollupRow::attribute)
                .thenComparing(RollupRow::counterName)
                .thenComparing(RollupRow::fromState)
                .thenComparing(RollupRow::toState));
        String sql =
                """
                insert into obsinity.object_transition_counters(
                    ts,
                    bucket,
                    service_id,
                    object_type,
                    attribute,
                    counter_name,
                    from_state,
                    to_state,
                    counter_value)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (ts, bucket, service_id, object_type, attribute, counter_name, from_state, to_state)
                do update set counter_value =
                    obsinity.object_transition_counters.counter_value + excluded.counter_value
                """;
        int attempts = 0;
        int maxAttempts = 6;
        while (true) {
            attempts++;
            try {
                jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        RollupRow row = rows.get(i);
                        ps.setTimestamp(1, Timestamp.from(row.timestamp()));
                        ps.setString(2, bucket.label());
                        ps.setObject(3, row.serviceId());
                        ps.setString(4, row.objectType());
                        ps.setString(5, row.attribute());
                        ps.setString(6, row.counterName());
                        ps.setString(7, row.fromState());
                        ps.setString(8, row.toState());
                        ps.setLong(9, row.delta());
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                });
                return;
            } catch (DataAccessException ex) {
                if (attempts >= maxAttempts || !isDeadlock(ex)) {
                    throw ex;
                }
                long base = 50L << Math.min(attempts, 6);
                long jitter = ThreadLocalRandom.current().nextLong(base, base * 2);
                long backoffMs = Math.min(jitter, 5_000L);
                log.warn(
                        "Deadlock detected while persisting transition counters bucket {}. Retrying attempt {}/{} after {} ms",
                        bucket.label(),
                        attempts + 1,
                        maxAttempts,
                        backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private boolean isDeadlock(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sqlEx && "40P01".equals(sqlEx.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}

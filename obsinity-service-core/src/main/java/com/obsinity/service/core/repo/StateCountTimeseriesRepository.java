package com.obsinity.service.core.repo;

import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.repo.ObjectStateCountRepository.StateCountSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StateCountTimeseriesRepository {

    private final JdbcTemplate jdbcTemplate;

    public void upsertBatch(Instant timestamp, CounterBucket bucket, List<StateCountSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
            INSERT INTO obsinity.object_state_count_timeseries(
                ts, bucket, service_id, object_type, attribute, state_value, state_count)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (ts, bucket, service_id, object_type, attribute, state_value)
            DO UPDATE SET state_count = EXCLUDED.state_count
            """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        StateCountSnapshot snapshot = snapshots.get(i);
                        ps.setTimestamp(1, Timestamp.from(timestamp));
                        ps.setString(2, bucket.label());
                        ps.setObject(3, snapshot.serviceId());
                        ps.setString(4, snapshot.objectType());
                        ps.setString(5, snapshot.attribute());
                        ps.setString(6, snapshot.stateValue());
                        ps.setLong(7, snapshot.count());
                    }

                    @Override
                    public int getBatchSize() {
                        return snapshots.size();
                    }
                });
    }
}

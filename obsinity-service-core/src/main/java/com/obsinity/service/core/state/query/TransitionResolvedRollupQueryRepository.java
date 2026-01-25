package com.obsinity.service.core.state.query;

import com.obsinity.service.core.counter.CounterBucket;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransitionResolvedRollupQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public TransitionResolvedRollupQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long sumCounter(
            UUID serviceId,
            String objectType,
            String attribute,
            String counterName,
            String fromState,
            String toState,
            CounterBucket bucket,
            Instant windowStart,
            Instant windowEnd) {
        if (serviceId == null
                || objectType == null
                || attribute == null
                || counterName == null
                || toState == null
                || bucket == null
                || windowStart == null
                || windowEnd == null) {
            return 0L;
        }
        String resolvedFromState =
                com.obsinity.service.core.state.transition.counter.TransitionCounterMetricKey.storageFromState(
                        fromState);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute)
                .addValue("counter_name", counterName)
                .addValue("from_state", resolvedFromState)
                .addValue("to_state", toState)
                .addValue("bucket", bucket.label())
                .addValue("start", java.sql.Timestamp.from(windowStart))
                .addValue("end", java.sql.Timestamp.from(windowEnd));
        Long result = jdbc.queryForObject(
                """
            select coalesce(sum(counter_value), 0)
            from obsinity.object_transition_counters
            where service_id = :service_id
              and object_type = :object_type
              and attribute = :attribute
              and counter_name = :counter_name
              and from_state = :from_state
              and to_state = :to_state
              and bucket = :bucket
              and ts >= :start
              and ts < :end
            """,
                params,
                Long.class);
        return result == null ? 0L : result;
    }
}

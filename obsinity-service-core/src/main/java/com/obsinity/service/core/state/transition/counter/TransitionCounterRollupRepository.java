package com.obsinity.service.core.state.transition.counter;

import com.obsinity.service.core.counter.CounterBucket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransitionCounterRollupRepository {
    void applyBatch(CounterBucket bucket, List<RollupRow> rows);

    record RollupRow(
            Instant timestamp,
            UUID serviceId,
            String objectType,
            String attribute,
            String counterName,
            String fromState,
            String toState,
            long delta) {}
}

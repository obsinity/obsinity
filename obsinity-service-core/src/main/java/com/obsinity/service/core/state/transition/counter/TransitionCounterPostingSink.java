package com.obsinity.service.core.state.transition.counter;

import java.time.Instant;

public interface TransitionCounterPostingSink {
    void post(TransitionCounterMetricKey key, Instant timestamp, long delta, String postingId);
}

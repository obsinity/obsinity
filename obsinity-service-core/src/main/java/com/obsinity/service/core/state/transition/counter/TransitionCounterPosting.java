package com.obsinity.service.core.state.transition.counter;

import java.time.Instant;

public record TransitionCounterPosting(
        TransitionCounterMetricKey key, Instant timestamp, long delta, String postingId) {}

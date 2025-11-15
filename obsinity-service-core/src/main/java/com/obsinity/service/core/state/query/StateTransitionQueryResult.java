package com.obsinity.service.core.state.query;

import java.time.Instant;
import java.util.List;

public record StateTransitionQueryResult(
        List<StateTransitionQueryWindow> windows,
        int offset,
        int limit,
        int totalIntervals,
        Instant start,
        Instant end) {}

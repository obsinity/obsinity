package com.obsinity.service.core.histogram;

import java.time.Instant;
import java.util.List;

public record HistogramQueryResult(
        List<HistogramQueryWindow> windows,
        int offset,
        int limit,
        int totalWindows,
        List<Double> defaultPercentiles,
        Instant start,
        Instant end) {}

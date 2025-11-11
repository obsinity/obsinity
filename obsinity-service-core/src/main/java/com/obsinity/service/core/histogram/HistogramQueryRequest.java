package com.obsinity.service.core.histogram;

import java.util.List;
import java.util.Map;

/** Request payload for histogram query execution. */
public record HistogramQueryRequest(
        String serviceKey,
        String eventType,
        String histogramName,
        Map<String, List<String>> key,
        String interval,
        String start,
        String end,
        List<Double> percentiles,
        Limits limits) {

    public record Limits(Integer offset, Integer limit) {}
}

package com.obsinity.service.core.state.query;

import java.time.Instant;
import java.util.List;

public record RatioQueryResult(
        String name, String serviceKey, Instant from, Instant to, long total, List<RatioSlice> slices) {

    public record RatioSlice(String label, Number value, Double ratio, Double percent, Long rawValue) {}
}

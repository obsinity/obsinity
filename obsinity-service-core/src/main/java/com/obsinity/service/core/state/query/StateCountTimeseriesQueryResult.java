package com.obsinity.service.core.state.query;

import java.time.Instant;
import java.util.List;

public record StateCountTimeseriesQueryResult(
        List<StateCountTimeseriesWindow> windows,
        int offset,
        int limit,
        int totalIntervals,
        Instant start,
        Instant end) {

    public record StateCountTimeseriesWindow(String start, String end, List<Entry> states) {
        public record Entry(String state, long count) {}
    }
}

package com.obsinity.service.core.histogram;

import java.util.List;
import java.util.Map;

public record HistogramQueryWindow(String from, String to, List<Series> series) {

    public record Series(
            Map<String, String> key,
            long samples,
            double sum,
            Double mean,
            Map<Double, Double> percentiles,
            long overflowLow,
            long overflowHigh) {}
}

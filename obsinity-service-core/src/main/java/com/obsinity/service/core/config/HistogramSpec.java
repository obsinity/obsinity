package com.obsinity.service.core.config;

import com.obsinity.service.core.counter.CounterGranularity;
import java.util.List;

/** Normalized histogram specification parsed from the CRD definition. */
public record HistogramSpec(
        String valuePath, List<String> keyDimensions, SketchSpec sketchSpec, CounterGranularity granularity, List<Double> percentiles) {

    private static final SketchSpec DEFAULT_SKETCH = new SketchSpec("ddsketch", 0.01d, 0.0005d, Double.POSITIVE_INFINITY);
    private static final List<Double> DEFAULT_PERCENTILES = List.of(0.5d, 0.9d, 0.95d, 0.99d);

    public HistogramSpec {
        keyDimensions = keyDimensions == null ? List.of() : List.copyOf(keyDimensions);
        sketchSpec = sketchSpec == null ? DEFAULT_SKETCH : sketchSpec;
        granularity = granularity != null ? granularity : CounterGranularity.S5;
        percentiles = (percentiles == null || percentiles.isEmpty()) ? DEFAULT_PERCENTILES : List.copyOf(percentiles);
    }

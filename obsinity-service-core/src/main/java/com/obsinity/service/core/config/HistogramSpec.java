package com.obsinity.service.core.config;

import java.util.List;

/** Normalized histogram specification parsed from the CRD definition. */
public record HistogramSpec(String valuePath, List<String> keyDimensions, SketchSpec sketchSpec) {

    private static final SketchSpec DEFAULT_SKETCH = new SketchSpec("ddsketch", 0.01d, 0.0005d, 120.0d);

    public HistogramSpec {
        keyDimensions = keyDimensions == null ? List.of() : List.copyOf(keyDimensions);
        sketchSpec = sketchSpec == null ? DEFAULT_SKETCH : sketchSpec;
    }

    /** DDSketch configuration (kind retained for forward compatibility). */
    public record SketchSpec(String kind, double relativeAccuracy, double minValue, double maxValue) {

        public SketchSpec {
            kind = (kind == null || kind.isBlank()) ? "ddsketch" : kind.trim();
            relativeAccuracy = relativeAccuracy > 0 ? relativeAccuracy : 0.01d;
            minValue = minValue > 0 ? minValue : 0.0005d;
            maxValue = maxValue > minValue ? maxValue : Math.max(1.0d, minValue * 2);
        }

        public boolean isDdSketch() {
            return "DDSKETCH".equalsIgnoreCase(kind);
        }
    }

    public boolean hasValueOverride() {
        return valuePath != null && !valuePath.isBlank();
    }

    public boolean hasDimensions() {
        return keyDimensions != null && !keyDimensions.isEmpty();
    }
}

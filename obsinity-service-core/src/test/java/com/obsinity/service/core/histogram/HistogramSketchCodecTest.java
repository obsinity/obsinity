package com.obsinity.service.core.histogram;

import static org.assertj.core.api.Assertions.assertThat;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.DDSketches;
import org.junit.jupiter.api.Test;

class HistogramSketchCodecTest {

    @Test
    void roundTripPreservesQuantiles() {
        DDSketch sketch = DDSketches.unboundedDense(0.01);
        sketch.accept(10);
        sketch.accept(20);
        sketch.accept(30);

        byte[] payload = HistogramSketchCodec.serialize(sketch);
        DDSketch restored = HistogramSketchCodec.deserialize(payload);

        assertThat(restored.getValueAtQuantile(0.5)).isBetween(9.0, 31.0);
        assertThat(restored.getValueAtQuantile(0.9)).isGreaterThan(10.0);
    }
}

package com.obsinity.service.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.DDSketches;
import com.obsinity.service.core.config.HistogramSpec;
import com.obsinity.service.core.counter.CounterGranularity;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HistogramBuffer {

    private final EnumMap<CounterGranularity, ConcurrentMap<Long, ConcurrentMap<String, BufferedHistogramEntry>>>
            buffers = new EnumMap<>(CounterGranularity.class);

    public HistogramBuffer() {
        for (CounterGranularity granularity : CounterGranularity.values()) {
            buffers.put(granularity, new ConcurrentHashMap<>());
        }
    }

    public void recordSample(
            CounterGranularity granularity,
            long epoch,
            UUID histogramConfigId,
            UUID eventTypeId,
            String keyHash,
            Map<String, String> keyData,
            double sampleValue,
            HistogramSpec.SketchSpec sketchSpec) {
        ConcurrentMap<Long, ConcurrentMap<String, BufferedHistogramEntry>> granularityBuffer = buffers.get(granularity);
        ConcurrentMap<String, BufferedHistogramEntry> epochMap =
                granularityBuffer.computeIfAbsent(epoch, ignored -> new ConcurrentHashMap<>());
        epochMap.compute(keyHash, (key, existing) -> {
            if (existing == null) {
                DDSketch sketch = createSketch(sketchSpec);
                BufferedHistogramEntry entry = new BufferedHistogramEntry(
                        histogramConfigId, eventTypeId, keyHash, keyData, sketchSpec, sketch);
                entry.addSample(sampleValue);
                return entry;
            }
            existing.addSample(sampleValue);
            return existing;
        });
    }

    private DDSketch createSketch(HistogramSpec.SketchSpec sketchSpec) {
        if (sketchSpec == null || !sketchSpec.isDdSketch()) {
            throw new IllegalArgumentException("Only ddsketch histograms are supported at this stage");
        }
        return DDSketches.unboundedDense(sketchSpec.relativeAccuracy());
    }

    public ConcurrentMap<Long, ConcurrentMap<String, BufferedHistogramEntry>> getBuffer(
            CounterGranularity granularity) {
        return buffers.get(granularity);
    }

    public Map<String, BufferedHistogramEntry> removeEpoch(CounterGranularity granularity, long epoch) {
        ConcurrentMap<Long, ConcurrentMap<String, BufferedHistogramEntry>> granularityBuffer = buffers.get(granularity);
        ConcurrentMap<String, BufferedHistogramEntry> removed = granularityBuffer.remove(epoch);
        return removed != null ? removed : Map.of();
    }

    public void cleanupOldEntries(CounterGranularity granularity) {
        ConcurrentMap<Long, ConcurrentMap<String, BufferedHistogramEntry>> granularityBuffer = buffers.get(granularity);
        granularityBuffer.forEach((epoch, map) -> {
            if (map.isEmpty()) {
                return;
            }
            map.entrySet().removeIf(entry -> entry.getValue().getSamples() == 0);
            if (map.isEmpty()) {
                granularityBuffer.remove(epoch);
            }
        });
        if (log.isDebugEnabled()) {
            log.debug(
                    "Histogram buffer cleanup complete granularity={} remainingEpochs={}",
                    granularity,
                    granularityBuffer.size());
        }
    }

    @Getter
    public static final class BufferedHistogramEntry {
        private final UUID histogramConfigId;
        private final UUID eventTypeId;
        private final String keyHash;
        private final Map<String, String> keyData;
        private final HistogramSpec.SketchSpec sketchSpec;
        private final DDSketch sketch;
        private double sum;
        private long samples;

        private BufferedHistogramEntry(
                UUID histogramConfigId,
                UUID eventTypeId,
                String keyHash,
                Map<String, String> keyData,
                HistogramSpec.SketchSpec sketchSpec,
                DDSketch sketch) {
            this.histogramConfigId = histogramConfigId;
            this.eventTypeId = eventTypeId;
            this.keyHash = keyHash;
            this.keyData = keyData != null ? Map.copyOf(keyData) : Map.of();
            this.sketchSpec = sketchSpec;
            this.sketch = sketch;
        }

        public void addSample(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return;
            }
            sketch.accept(value);
            sum += value;
            samples++;
        }


        public double mean() {
            return samples == 0 ? 0.0d : sum / samples;
        }
    }
}

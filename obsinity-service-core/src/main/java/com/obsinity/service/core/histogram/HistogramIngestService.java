package com.obsinity.service.core.histogram;

import com.obsinity.service.core.config.EventTypeConfig;
import com.obsinity.service.core.config.HistogramConfig;
import com.obsinity.service.core.config.HistogramSpec;
import com.obsinity.service.core.counter.CounterGranularity;
import com.obsinity.service.core.counter.CounterHashService;
import com.obsinity.service.core.model.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistogramIngestService {

    private final HistogramBuffer buffer;
    private final CounterHashService hashService;

    public void process(EventEnvelope envelope, EventTypeConfig eventConfig) {
        List<HistogramConfig> histograms = eventConfig.histograms();
        if (histograms == null || histograms.isEmpty()) {
            return;
        }

        Map<String, Object> attributes = normalizeAttributes(envelope.getAttributes());
        UUID eventTypeId = eventConfig.eventId();
        Instant occurredAt = Objects.requireNonNullElse(envelope.getTimestamp(), Instant.now());

        for (HistogramConfig histogram : histograms) {
            HistogramSpec spec = histogram.spec();
            if (spec == null) {
                continue;
            }

            CounterGranularity granularity = spec.granularity();
            double sample = resolveSampleValue(envelope, attributes, spec);
            if (Double.isNaN(sample) || sample < 0) {
                continue;
            }

            if (spec != null && spec.sketchSpec() != null) {
                HistogramSpec.SketchSpec sketchSpec = spec.sketchSpec();
                if (sample < sketchSpec.minValue() || sample > sketchSpec.maxValue()) {
                    log.debug(
                            "Skipping histogram sample outside configured bounds metric={} value={}",
                            histogram.name(),
                            sample);
                    continue;
                }
            }

            Map<String, String> dimensionValues = extractKeyData(spec.keyDimensions(), attributes);
            String keyHash = hashService.getOrCreateHash(dimensionValues);
            Instant aligned = granularity.baseBucket().align(occurredAt);
            long epoch = aligned.getEpochSecond();

            buffer.recordSample(
                    granularity,
                    epoch,
                    histogram.id(),
                    eventTypeId,
                    keyHash,
                    dimensionValues,
                    sample,
                    spec.sketchSpec());
        }
    }

    private double resolveSampleValue(EventEnvelope envelope, Map<String, Object> attributes, HistogramSpec spec) {
        if (spec.hasValueOverride()) {
            Object value = resolveAttribute(attributes, spec.valuePath());
            Double numeric = coerceToDouble(value);
            if (numeric != null) {
                return numeric;
            }
        }
        Instant start = envelope.getTimestamp();
        Instant end = envelope.getEndTimestamp();
        if (start != null && end != null) {
            return Duration.between(start, end).toNanos() / 1_000_000.0d;
        }
        return Double.NaN;
    }

    private Double coerceToDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ex) {
                log.debug("Unable to parse histogram sample value: {}", s, ex);
                return null;
            }
        }
        return null;
    }

    private Map<String, String> extractKeyData(List<String> keyDimensions, Map<String, Object> attributes) {
        if (keyDimensions == null || keyDimensions.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : keyDimensions) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = resolveAttribute(attributes, key);
            if (value == null) {
                return Map.of();
            }
            values.put(key, stringify(value));
        }
        return Map.copyOf(values);
    }

    private Object resolveAttribute(Map<String, Object> attributes, String path) {
        if (attributes == null || path == null || path.isBlank()) {
            return null;
        }
        if (!path.contains(".")) {
            return attributes.get(path);
        }
        String[] segments = path.split("\\.");
        Object current = attributes;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        attributes.forEach((key, value) -> mergeAttribute(normalized, key, value));
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private void mergeAttribute(Map<String, Object> target, String rawKey, Object value) {
        if (rawKey == null || rawKey.isBlank()) return;
        String key = rawKey.trim();

        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> child = target.containsKey(key) && target.get(key) instanceof Map<?, ?> existing
                    ? toMutableMap((Map<?, ?>) existing)
                    : new LinkedHashMap<>();
            mapValue.forEach((k, v) -> {
                if (k != null) mergeAttribute(child, k.toString(), v);
            });
            target.put(key, child);
            return;
        }

        if (key.contains(".")) {
            String[] segments = key.split("\\.");
            Map<String, Object> current = target;
            for (int i = 0; i < segments.length - 1; i++) {
                String segment = segments[i];
                Object next = current.get(segment);
                if (!(next instanceof Map<?, ?> nextMap)) {
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    current.put(segment, fresh);
                    current = fresh;
                } else {
                    Map<String, Object> mutable = toMutableMap(nextMap);
                    current.put(segment, mutable);
                    current = mutable;
                }
            }
            current.put(segments[segments.length - 1], value);
            return;
        }

        target.put(key, value);
    }

    private Map<String, Object> toMutableMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k != null ? k.toString() : null, v));
        return copy;
    }
}

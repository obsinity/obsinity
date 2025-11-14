package com.obsinity.service.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.counter.CounterGranularity;
import com.obsinity.service.core.model.config.EventConfig;
import com.obsinity.service.core.model.config.EventIndexConfig;
import com.obsinity.service.core.model.config.MetricConfig;
import com.obsinity.service.core.model.config.ServiceConfig;
import com.obsinity.service.core.model.config.StateExtractorConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Utility that converts resource-backed ServiceConfig models into runtime snapshots. */
public class ConfigMaterializer {
    private final ObjectMapper mapper;

    public ConfigMaterializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ServiceConfigView materializeService(
            ServiceConfig model, UUID serviceId, String serviceKey, Instant updatedAt) {
        Map<String, EventTypeConfig> eventTypes = new HashMap<>();
        if (model.events() != null) {
            for (EventConfig event : model.events()) {
                EventTypeConfig etc = materializeEvent(event, serviceKey, updatedAt);
                eventTypes.put(etc.eventType(), etc);
            }
        }
        List<StateExtractorDefinition> extractors = materializeStateExtractors(model.stateExtractors());
        return new ServiceConfigView(
                serviceId,
                serviceKey,
                updatedAt != null ? updatedAt : Instant.now(),
                Map.copyOf(eventTypes),
                extractors);
    }

    private EventTypeConfig materializeEvent(EventConfig cfg, String serviceKey, Instant updatedAt) {
        String name = safeTrim(cfg.eventName());
        if (name == null) {
            throw new IllegalArgumentException("Event name must be provided in config");
        }
        UUID eventId = cfg.uuid() != null ? cfg.uuid() : deterministicId(serviceKey, name);
        String eventNorm = cfg.eventNorm() != null ? cfg.eventNorm().trim() : name.toLowerCase(Locale.ROOT);

        List<IndexConfig> indexes = new ArrayList<>();
        EventIndexConfig idx = cfg.attributeIndex();
        if (idx != null && idx.specJson() != null && !idx.specJson().isEmpty()) {
            UUID indexId = idx.uuid() != null ? idx.uuid() : deterministicId(eventId.toString(), "index");
            indexes.add(new IndexConfig(indexId, "attribute-index", toJson(idx.specJson())));
        }

        List<CounterConfig> counters = new ArrayList<>();
        List<HistogramConfig> histograms = new ArrayList<>();
        if (cfg.metrics() != null) {
            for (MetricConfig metric : cfg.metrics()) {
                String type = metric.type() != null ? metric.type().trim().toUpperCase(Locale.ROOT) : "";
                UUID metricId =
                        metric.uuid() != null ? metric.uuid() : deterministicId(eventId.toString(), metric.name());
                JsonNode definition = toJson(metric.specJson());
                if ("HISTOGRAM".equals(type)) {
                    HistogramSpec spec = buildHistogramSpec(metric);
                    histograms.add(new HistogramConfig(metricId, metric.name(), spec));
                } else {
                    CounterGranularity granularity = resolveGranularity(metric);
                    List<String> keyedKeys = metric.keyedKeys() != null ? List.copyOf(metric.keyedKeys()) : List.of();
                    JsonNode filters = toJson(metric.filtersJson());
                    counters.add(
                            new CounterConfig(metricId, metric.name(), granularity, keyedKeys, definition, filters));
                }
            }
        }

        return new EventTypeConfig(
                eventId,
                name,
                eventNorm,
                safeTrim(cfg.category()),
                safeTrim(cfg.subCategory()),
                updatedAt != null ? updatedAt : Instant.now(),
                List.copyOf(indexes),
                List.copyOf(counters),
                List.copyOf(histograms));
    }

    private JsonNode toJson(Map<String, Object> map) {
        return mapper.valueToTree(map != null ? map : Map.of());
    }

    private HistogramSpec buildHistogramSpec(MetricConfig metric) {
        Map<String, Object> spec = metric.specJson();
        if (spec == null) {
            return new HistogramSpec(null, List.of(), null, CounterGranularity.S5, null);
        }

        String valuePath = stringValue(spec.get("value"));
        List<String> keyDimensions = extractDimensions(spec.get("key"));
        HistogramSpec.SketchSpec sketchSpec = extractSketchSpec(spec.get("sketch"));
        CounterGranularity granularity = extractHistogramGranularity(spec);
        List<Double> percentiles = extractPercentiles(spec);
        return new HistogramSpec(valuePath, keyDimensions, sketchSpec, granularity, percentiles);
    }

    private List<String> extractDimensions(Object keyNode) {
        Map<String, Object> keyMap = asMap(keyNode);
        if (keyMap == null) {
            return List.of();
        }
        Object dimensions = keyMap.containsKey("dimensions") ? keyMap.get("dimensions") : keyMap.get("dynamic");
        if (!(dimensions instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object value : list) {
            if (value == null) continue;
            String trimmed = value.toString().trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    private HistogramSpec.SketchSpec extractSketchSpec(Object sketchNode) {
        Map<String, Object> sketch = asMap(sketchNode);
        if (sketch == null || sketch.isEmpty()) {
            return null;
        }
        String kind = stringValue(sketch.get("kind"));
        double accuracy = doubleValue(sketch.get("relativeAccuracy"), 0.01d);
        return new HistogramSpec.SketchSpec(kind, accuracy);
    }

    private CounterGranularity extractHistogramGranularity(Map<String, Object> spec) {
        Map<String, Object> rollup = asMap(spec.get("rollup"));
        Map<String, Object> windowing = rollup != null ? asMap(rollup.get("windowing")) : null;
        List<String> granularities = windowing != null ? toStringList(windowing.get("granularities")) : List.of();
        if (granularities.isEmpty()) {
            return CounterGranularity.S5;
        }
        String smallest = granularities.stream()
                .filter(g -> g != null && !g.isBlank())
                .min((a, b) -> {
                    try {
                        long da = com.obsinity.service.core.counter.DurationParser.parse(a)
                                .toMillis();
                        long db = com.obsinity.service.core.counter.DurationParser.parse(b)
                                .toMillis();
                        return Long.compare(da, db);
                    } catch (Exception ex) {
                        return a.compareTo(b);
                    }
                })
                .orElse("S5");
        return CounterGranularity.fromConfigValue(smallest);
    }

    private List<Double> extractPercentiles(Map<String, Object> spec) {
        Map<String, Object> rollup = asMap(spec.get("rollup"));
        if (rollup == null) {
            return null;
        }
        Object percentilesNode = rollup.get("percentiles");
        if (!(percentilesNode instanceof List<?> list)) {
            return null;
        }
        List<Double> percentiles = new ArrayList<>();
        for (Object value : list) {
            if (value == null) continue;
            try {
                percentiles.add(Double.parseDouble(value.toString()));
            } catch (NumberFormatException ignore) {
            }
        }
        return percentiles.isEmpty() ? null : List.copyOf(percentiles);
    }

    private Map<String, Object> asMap(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null) {
                copy.put(k.toString(), v);
            }
        });
        return copy;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry == null) continue;
            String s = entry.toString().trim();
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        return List.copyOf(result);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignore) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private CounterGranularity resolveGranularity(MetricConfig metric) {
        String explicit = null;
        if (metric.specJson() != null) {
            Object value = metric.specJson().get("granularity");
            if (value instanceof String g && !g.isBlank()) {
                explicit = g;
            }
        }
        if (explicit == null && metric.rollups() != null && !metric.rollups().isEmpty()) {
            explicit = metric.rollups().stream()
                    .filter(v -> v != null && !v.isBlank())
                    .min((a, b) -> {
                        try {
                            long da = com.obsinity.service.core.counter.DurationParser.parse(a)
                                    .toMillis();
                            long db = com.obsinity.service.core.counter.DurationParser.parse(b)
                                    .toMillis();
                            return Long.compare(da, db);
                        } catch (Exception ex) {
                            return a.compareTo(b);
                        }
                    })
                    .orElse(null);
        }
        return CounterGranularity.fromConfigValue(explicit);
    }

    private static String safeTrim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static UUID deterministicId(String... parts) {
        String seed =
                java.util.Arrays.stream(parts).map(p -> p == null ? "" : p).collect(Collectors.joining("|"));
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private List<StateExtractorDefinition> materializeStateExtractors(List<StateExtractorConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }
        List<StateExtractorDefinition> out = new ArrayList<>();
        for (StateExtractorConfig cfg : configs) {
            if (cfg == null) continue;
            String rawType = safeTrim(cfg.rawType());
            String objectType = safeTrim(cfg.objectType());
            String objectIdField = safeTrim(cfg.objectIdField());
            List<String> attributes = normalizeStateAttributes(cfg.stateAttributes());
            if (rawType == null || objectType == null || objectIdField == null || attributes.isEmpty()) {
                continue;
            }
            out.add(new StateExtractorDefinition(rawType, objectType, objectIdField, attributes));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private List<String> normalizeStateAttributes(List<String> stateAttributes) {
        if (stateAttributes == null || stateAttributes.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String attr : stateAttributes) {
            String trimmed = safeTrim(attr);
            if (trimmed != null) {
                cleaned.add(trimmed);
            }
        }
        return cleaned.isEmpty() ? List.of() : List.copyOf(cleaned);
    }

    /** Immutable projection so callers do not depend on runtime record constructors. */
    public record ServiceConfigView(
            UUID serviceId,
            String serviceKey,
            Instant updatedAt,
            Map<String, EventTypeConfig> eventTypes,
            List<StateExtractorDefinition> stateExtractors) {}
}

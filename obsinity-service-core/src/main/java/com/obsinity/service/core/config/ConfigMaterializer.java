package com.obsinity.service.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.model.config.EventConfig;
import com.obsinity.service.core.model.config.EventIndexConfig;
import com.obsinity.service.core.model.config.MetricConfig;
import com.obsinity.service.core.model.config.ServiceConfig;
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
        return new ServiceConfigView(
                serviceId, serviceKey, updatedAt != null ? updatedAt : Instant.now(), Map.copyOf(eventTypes));
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
                    histograms.add(new HistogramConfig(metricId, metric.name(), definition));
                } else {
                    counters.add(new CounterConfig(metricId, metric.name(), definition));
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

    /** Immutable projection so callers do not depend on runtime record constructors. */
    public record ServiceConfigView(
            UUID serviceId, String serviceKey, Instant updatedAt, Map<String, EventTypeConfig> eventTypes) {}
}

package com.obsinity.service.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Convenience reader API for callers that need a single event-type config.
 */
@Service
public class ConfigLookup {
    private final ConfigRegistry registry;

    public ConfigLookup(ConfigRegistry registry) {
        this.registry = registry;
    }

    public Optional<EventTypeConfig> get(java.util.UUID serviceId, String eventType) {
        var snap = registry.current();
        var svc = snap.services().get(serviceId);
        if (svc == null) return Optional.empty();
        EventTypeConfig direct = svc.eventTypes().get(eventType);
        if (direct != null) return Optional.of(direct);
        if (eventType != null) {
            String norm = eventType.toLowerCase(java.util.Locale.ROOT);
            for (EventTypeConfig cfg : svc.eventTypes().values()) {
                if (norm.equals(cfg.eventNorm())) {
                    return Optional.of(cfg);
                }
            }
        }
        return Optional.empty();
    }

    public boolean isServiceConfigured(java.util.UUID serviceId) {
        if (serviceId == null) return false;
        return registry.current().services().containsKey(serviceId);
    }

    public List<StateExtractorDefinition> stateExtractors(java.util.UUID serviceId, String rawType) {
        if (serviceId == null) {
            return List.of();
        }
        var svc = registry.current().services().get(serviceId);
        if (svc == null
                || svc.stateExtractors() == null
                || svc.stateExtractors().isEmpty()) {
            return List.of();
        }
        if (rawType == null || rawType.isBlank()) {
            return svc.stateExtractors();
        }
        String norm = rawType.toLowerCase(Locale.ROOT);
        List<StateExtractorDefinition> matches = new ArrayList<>();
        for (StateExtractorDefinition extractor : svc.stateExtractors()) {
            if (extractor == null || extractor.rawType() == null) continue;
            if (norm.equals(extractor.rawType().toLowerCase(Locale.ROOT))) {
                matches.add(extractor);
            }
        }
        return matches.isEmpty() ? List.of() : List.copyOf(matches);
    }

    public Optional<RatioQueryDefinition> ratioQuery(java.util.UUID serviceId, String queryName) {
        if (serviceId == null || queryName == null || queryName.isBlank()) {
            return Optional.empty();
        }
        var svc = registry.current().services().get(serviceId);
        if (svc == null) {
            return Optional.empty();
        }
        Map<String, RatioQueryDefinition> ratioQueries = svc.ratioQueries();
        if (ratioQueries == null || ratioQueries.isEmpty()) {
            return Optional.empty();
        }
        RatioQueryDefinition direct = ratioQueries.get(queryName);
        if (direct != null) {
            return Optional.of(direct);
        }
        String normalized = queryName.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, RatioQueryDefinition> entry : ratioQueries.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.ofNullable(entry.getValue());
            }
        }
        return Optional.empty();
    }
}

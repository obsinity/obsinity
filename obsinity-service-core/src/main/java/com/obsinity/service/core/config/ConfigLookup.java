package com.obsinity.service.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public List<StateExtractorDefinition> stateExtractorsByObjectType(java.util.UUID serviceId, String objectType) {
        if (serviceId == null || objectType == null || objectType.isBlank()) {
            return List.of();
        }
        var svc = registry.current().services().get(serviceId);
        if (svc == null
                || svc.stateExtractors() == null
                || svc.stateExtractors().isEmpty()) {
            return List.of();
        }
        List<StateExtractorDefinition> matches = new ArrayList<>();
        for (StateExtractorDefinition extractor : svc.stateExtractors()) {
            if (extractor == null || extractor.objectType() == null) continue;
            if (objectType.equals(extractor.objectType())) {
                matches.add(extractor);
            }
        }
        return matches.isEmpty() ? List.of() : List.copyOf(matches);
    }

    public List<InferenceRuleDefinition> inferenceRules(java.util.UUID serviceId, String objectType) {
        if (serviceId == null) {
            return List.of();
        }
        var svc = registry.current().services().get(serviceId);
        if (svc == null || svc.inferenceRules() == null || svc.inferenceRules().isEmpty()) {
            return List.of();
        }
        if (objectType == null || objectType.isBlank()) {
            return svc.inferenceRules();
        }
        List<InferenceRuleDefinition> matches = new ArrayList<>();
        for (InferenceRuleDefinition rule : svc.inferenceRules()) {
            if (rule == null || rule.objectType() == null) continue;
            if (objectType.equals(rule.objectType())) {
                matches.add(rule);
            }
        }
        return matches.isEmpty() ? List.of() : List.copyOf(matches);
    }

    public List<TransitionCounterDefinition> transitionCounters(java.util.UUID serviceId, String objectType) {
        if (serviceId == null) {
            return List.of();
        }
        var svc = registry.current().services().get(serviceId);
        if (svc == null
                || svc.transitionCounters() == null
                || svc.transitionCounters().isEmpty()) {
            return List.of();
        }
        if (objectType == null || objectType.isBlank()) {
            return svc.transitionCounters();
        }
        List<TransitionCounterDefinition> matches = new ArrayList<>();
        for (TransitionCounterDefinition counter : svc.transitionCounters()) {
            if (counter == null || counter.objectType() == null) continue;
            if (objectType.equals(counter.objectType())) {
                matches.add(counter);
            }
        }
        return matches.isEmpty() ? List.of() : List.copyOf(matches);
    }

    public java.util.Set<String> terminalStates(java.util.UUID serviceId, String objectType) {
        if (serviceId == null || objectType == null || objectType.isBlank()) {
            return java.util.Set.of();
        }
        var svc = registry.current().services().get(serviceId);
        if (svc == null
                || svc.stateExtractors() == null
                || svc.stateExtractors().isEmpty()) {
            return java.util.Set.of();
        }
        java.util.LinkedHashSet<String> terminals = new java.util.LinkedHashSet<>();
        for (StateExtractorDefinition extractor : svc.stateExtractors()) {
            if (extractor == null || extractor.objectType() == null) continue;
            if (!objectType.equals(extractor.objectType())) continue;
            List<String> values = extractor.terminalStates();
            if (values == null || values.isEmpty()) continue;
            for (String state : values) {
                if (state != null && !state.isBlank()) {
                    terminals.add(state);
                }
            }
        }
        return terminals.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(terminals);
    }
}

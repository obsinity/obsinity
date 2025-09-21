package com.obsinity.service.core.config;

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
        return Optional.ofNullable(svc.eventTypes().get(eventType));
    }
}

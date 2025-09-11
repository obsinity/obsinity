package com.obsinity.service.core.model.config;

import java.util.List;
import java.util.UUID;

/** A single event folder’s content. */
public record EventConfig(
        UUID uuid, // preferred UUIDv7 (optional)
        String eventName,
        String eventNorm, // lowercased; if null, derived from eventName
        List<MetricConfig> metrics,
        EventIndexConfig attributeIndex) {}

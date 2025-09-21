package com.obsinity.service.core.model.config;

import java.util.List;
import java.util.UUID;

/** A single event folder’s content. */
public record EventConfig(
        UUID uuid, // preferred UUIDv7 (optional)
        String eventName,
        String eventNorm, // lowercased; if null, derived from eventName
        String category, // optional category for the event
        String subCategory, // optional sub-category for the event
        List<MetricConfig> metrics,
        EventIndexConfig attributeIndex,
        String retentionTtl // optional TTL (e.g., "7d", "30d")
        ) {}

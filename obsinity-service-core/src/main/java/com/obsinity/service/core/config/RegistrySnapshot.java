package com.obsinity.service.core.config;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable, fully-materialized view of config:
 *   service -> eventType -> { indexes, counters, histograms }
 *
 * Kept tiny and allocation-friendly by using Java records and unmodifiable collections.
 */
public record RegistrySnapshot(Map<String, ServiceConfig> services, Instant loadedAt) {
    public static RegistrySnapshot empty() {
        return new RegistrySnapshot(Map.of(), Instant.EPOCH);
    }
}

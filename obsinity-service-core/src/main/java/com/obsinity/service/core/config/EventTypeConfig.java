package com.obsinity.service.core.config;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Materialized view of a single event type under a service.
 */
public record EventTypeConfig(
        UUID eventId,
        String eventType,
        Instant updatedAt,
        List<IndexConfig> indexes,
        List<CounterConfig> counters,
        List<HistogramConfig> histograms) {}

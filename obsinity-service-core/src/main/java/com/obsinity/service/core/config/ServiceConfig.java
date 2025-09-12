package com.obsinity.service.core.config;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Materialized view of a single service and its event types.
 */
public record ServiceConfig(
        UUID serviceId, String serviceShort, Instant updatedAt, Map<String, EventTypeConfig> eventTypes) {}

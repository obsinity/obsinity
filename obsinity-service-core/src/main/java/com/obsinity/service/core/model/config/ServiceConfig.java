package com.obsinity.service.core.model.config;

import java.time.Instant;
import java.util.List;

public record ServiceConfig(
        String service, String snapshotId, Instant createdAt, Defaults defaults, List<EventConfig> events) {

    public record Defaults(List<String> rollups, String backfillWindow) {}

    // Optional: convenience constant & factory
    public static final Defaults EMPTY_DEFAULTS = new Defaults(List.of(), null);

    public static ServiceConfig of(String service, String snapshotId, List<EventConfig> events) {
        return new ServiceConfig(service, snapshotId, Instant.now(), EMPTY_DEFAULTS, events);
    }
}

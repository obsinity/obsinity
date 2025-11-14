package com.obsinity.service.core.model.config;

import java.time.Instant;
import java.util.List;

public record ServiceConfig(
        String service,
        String snapshotId,
        Instant createdAt,
        Defaults defaults,
        List<EventConfig> events,
        List<StateExtractorConfig> stateExtractors) {

    public record Defaults(List<String> rollups, String backfillWindow) {}

    // Optional: convenience constant & factory
    public static final Defaults EMPTY_DEFAULTS = new Defaults(List.of(), null);

    public static ServiceConfig of(String service, String snapshotId, List<EventConfig> events) {
        return of(service, snapshotId, events, List.of());
    }

    public static ServiceConfig of(
            String service, String snapshotId, List<EventConfig> events, List<StateExtractorConfig> stateExtractors) {
        List<StateExtractorConfig> safeExtractors = stateExtractors == null ? List.of() : List.copyOf(stateExtractors);
        return new ServiceConfig(service, snapshotId, Instant.now(), EMPTY_DEFAULTS, events, safeExtractors);
    }
}

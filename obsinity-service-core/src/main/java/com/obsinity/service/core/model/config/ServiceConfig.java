package com.obsinity.service.core.model.config;

import java.time.Instant;
import java.util.List;

public record ServiceConfig(
        String service,
        String snapshotId,
        Instant createdAt,
        Defaults defaults,
        List<EventConfig> events,
        List<StateExtractorConfig> stateExtractors,
        List<TransitionCounterConfig> transitionCounters,
        List<InferenceRuleConfig> inferenceRules) {

    public record Defaults(List<String> rollups, String backfillWindow) {}

    // Optional: convenience constant & factory
    public static final Defaults EMPTY_DEFAULTS = new Defaults(List.of(), null);

    public static ServiceConfig of(String service, String snapshotId, List<EventConfig> events) {
        return of(service, snapshotId, events, List.of(), List.of(), List.of());
    }

    public static ServiceConfig of(
            String service, String snapshotId, List<EventConfig> events, List<StateExtractorConfig> stateExtractors) {
        return of(service, snapshotId, events, stateExtractors, List.of(), List.of());
    }

    public static ServiceConfig of(
            String service,
            String snapshotId,
            List<EventConfig> events,
            List<StateExtractorConfig> stateExtractors,
            List<TransitionCounterConfig> transitionCounters,
            List<InferenceRuleConfig> inferenceRules) {
        List<StateExtractorConfig> safeExtractors = stateExtractors == null ? List.of() : List.copyOf(stateExtractors);
        List<TransitionCounterConfig> safeCounters =
                transitionCounters == null ? List.of() : List.copyOf(transitionCounters);
        List<InferenceRuleConfig> safeInference = inferenceRules == null ? List.of() : List.copyOf(inferenceRules);
        return new ServiceConfig(
                service,
                snapshotId,
                Instant.now(),
                EMPTY_DEFAULTS,
                events,
                safeExtractors,
                safeCounters,
                safeInference);
    }
}

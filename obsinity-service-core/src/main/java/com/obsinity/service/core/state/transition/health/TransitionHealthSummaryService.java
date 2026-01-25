package com.obsinity.service.core.state.transition.health;

import com.obsinity.service.core.state.transition.inference.TransitionInferenceStatus;
import com.obsinity.service.core.state.transition.telemetry.TransitionTelemetryRegistry;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class TransitionHealthSummaryService {
    private final TransitionOpsHealthRepository repository;
    private final TransitionTelemetryRegistry telemetry;
    private final TransitionInferenceStatus inferenceStatus;

    public TransitionHealthSummaryService(
            TransitionOpsHealthRepository repository,
            TransitionTelemetryRegistry telemetry,
            TransitionInferenceStatus inferenceStatus) {
        this.repository = repository;
        this.telemetry = telemetry;
        this.inferenceStatus = inferenceStatus;
    }

    public TransitionHealthSummary summary() {
        TransitionTelemetryRegistry.Snapshot snapshot = telemetry.snapshot();
        long activeSynthetic = repository.countSyntheticByStatus("ACTIVE");
        long supersededSynthetic = repository.countSyntheticByStatus("SUPERSEDED");
        long dedupStore = repository.countPostingDedupStore();
        double meanSupersedeMillis = snapshot.timeToSupersedeCount() == 0
                ? 0.0
                : (double) snapshot.timeToSupersedeMillis() / (double) snapshot.timeToSupersedeCount();
        Instant lastInferenceSuccess = inferenceStatus.lastSuccess();
        Instant lastInferenceAttempt = inferenceStatus.lastAttempt();

        return new TransitionHealthSummary(
                activeSynthetic,
                supersededSynthetic,
                snapshot.syntheticInjections(),
                snapshot.syntheticSuperseded(),
                snapshot.fanoutTruncations(),
                snapshot.seenStatesCapExceeded(),
                snapshot.postingDedupHits(),
                dedupStore,
                meanSupersedeMillis,
                lastInferenceAttempt,
                lastInferenceSuccess);
    }

    public record TransitionHealthSummary(
            long activeSyntheticTerminals,
            long supersededSyntheticTerminals,
            long syntheticInjectionsTotal,
            long syntheticSupersededTotal,
            long fanoutTruncationsTotal,
            long seenStatesCapExceededTotal,
            long postingDedupHitsTotal,
            long postingDedupStoreSize,
            double meanTimeToSupersedeMillis,
            Instant lastInferenceAttempt,
            Instant lastInferenceSuccess) {}
}

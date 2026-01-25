package com.obsinity.service.core.state.transition.inference;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TransitionInferenceScheduler {
    private final TransitionInferenceService inferenceService;
    private final TransitionInferenceStatus status;

    @Value("${obsinity.stateTransitions.inference.enabled:true}")
    private boolean enabled;

    @Value("${obsinity.stateTransitions.inference.batchSize:500}")
    private int batchSize;

    @Scheduled(fixedRateString = "${obsinity.stateTransitions.inference.rateMillis:60000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        status.markAttempt(now);
        inferenceService.runOnce(now, batchSize);
        status.markSuccess(now);
    }
}

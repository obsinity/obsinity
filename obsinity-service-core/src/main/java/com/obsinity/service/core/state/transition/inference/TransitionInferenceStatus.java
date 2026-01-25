package com.obsinity.service.core.state.transition.inference;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class TransitionInferenceStatus {
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastAttempt = new AtomicReference<>();

    public void markAttempt(Instant instant) {
        lastAttempt.set(instant);
    }

    public void markSuccess(Instant instant) {
        lastSuccess.set(instant);
    }

    public Instant lastSuccess() {
        return lastSuccess.get();
    }

    public Instant lastAttempt() {
        return lastAttempt.get();
    }
}

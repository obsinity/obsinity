package com.obsinity.service.core.state.transition.inference;

import java.time.Instant;
import java.util.UUID;

public record TransitionInferenceCandidate(
        UUID serviceId,
        String objectType,
        String objectId,
        String attribute,
        String lastState,
        Instant lastEventTs,
        String terminalState) {}

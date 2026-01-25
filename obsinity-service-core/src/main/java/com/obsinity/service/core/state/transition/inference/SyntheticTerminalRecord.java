package com.obsinity.service.core.state.transition.inference;

import java.time.Instant;
import java.util.UUID;

public record SyntheticTerminalRecord(
        UUID serviceId,
        String objectType,
        String objectId,
        String attribute,
        String ruleId,
        String syntheticEventId,
        Instant syntheticTs,
        String syntheticState,
        String emitServiceId,
        String reason,
        String origin,
        String status,
        Instant lastEventTs,
        String lastState,
        String supersededByEventId,
        Instant supersededAt,
        Instant reversedAt,
        java.util.List<com.obsinity.service.core.state.transition.counter.TransitionCounterFootprintEntry>
                transitionFootprint) {}

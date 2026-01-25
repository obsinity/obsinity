package com.obsinity.service.core.state.transition.counter;

import java.time.Instant;

public record TransitionCounterSnapshot(
        String lastState, SeenStates seenStates, Instant lastEventTs, String terminalState) {}

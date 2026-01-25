package com.obsinity.service.core.state.transition.counter;

import java.time.Instant;
import java.util.UUID;

public interface TransitionCounterSnapshotStore {
    TransitionCounterSnapshot find(UUID serviceId, String objectType, String objectId, String attribute);

    void upsert(
            UUID serviceId,
            String objectType,
            String objectId,
            String attribute,
            String lastState,
            SeenStates seenStates,
            Instant lastEventTs,
            String terminalState);
}

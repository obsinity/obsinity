package com.obsinity.service.core.state.transition.inference;

import com.obsinity.service.core.state.transition.counter.TransitionCounterFootprintRecorder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SyntheticTerminalRecordRepository extends TransitionCounterFootprintRecorder {
    boolean insertIfEligible(SyntheticTerminalRecord record, Instant expectedLastEventTs);

    List<SyntheticTerminalRecord> findActive(UUID serviceId, String objectType, String objectId, String attribute);

    boolean supersede(String syntheticEventId, String supersededByEventId, Instant supersededAt);

    void markReversed(String syntheticEventId, Instant reversedAt);
}

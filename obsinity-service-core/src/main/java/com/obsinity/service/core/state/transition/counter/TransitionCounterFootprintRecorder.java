package com.obsinity.service.core.state.transition.counter;

import java.util.List;

public interface TransitionCounterFootprintRecorder {
    void recordFootprint(String syntheticEventId, List<TransitionCounterFootprintEntry> entries);
}

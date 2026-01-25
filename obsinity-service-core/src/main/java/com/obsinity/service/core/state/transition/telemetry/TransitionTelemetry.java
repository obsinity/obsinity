package com.obsinity.service.core.state.transition.telemetry;

import java.time.Duration;

public interface TransitionTelemetry {
    void recordSyntheticInjection(String objectType, String ruleId, String state);

    void adjustSyntheticActive(String objectType, String ruleId, long delta);

    void recordSyntheticSuperseded(String objectType, String ruleId, Duration timeToSupersede);

    void recordFanoutTruncation(String objectType, String counterName, int originalSize, int truncatedSize);

    void recordSeenStatesCapExceeded(String objectType, String attribute, int cap);

    void recordPostingDedupHits(long hits);
}

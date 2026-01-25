package com.obsinity.service.core.state.transition.telemetry;

import java.time.Duration;

public class NoopTransitionTelemetry implements TransitionTelemetry {
    @Override
    public void recordSyntheticInjection(String objectType, String ruleId, String state) {}

    @Override
    public void adjustSyntheticActive(String objectType, String ruleId, long delta) {}

    @Override
    public void recordSyntheticSuperseded(String objectType, String ruleId, Duration timeToSupersede) {}

    @Override
    public void recordFanoutTruncation(String objectType, String counterName, int originalSize, int truncatedSize) {}

    @Override
    public void recordSeenStatesCapExceeded(String objectType, String attribute, int cap) {}

    @Override
    public void recordPostingDedupHits(long hits) {}
}

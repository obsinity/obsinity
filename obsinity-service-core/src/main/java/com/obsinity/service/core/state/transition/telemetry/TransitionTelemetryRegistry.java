package com.obsinity.service.core.state.transition.telemetry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class TransitionTelemetryRegistry implements TransitionTelemetry {
    private final LongAdder syntheticInjections = new LongAdder();
    private final LongAdder syntheticSuperseded = new LongAdder();
    private final LongAdder fanoutTruncations = new LongAdder();
    private final LongAdder seenStatesCapExceeded = new LongAdder();
    private final LongAdder postingDedupHits = new LongAdder();

    private final Map<RuleKey, LongAdder> activeByRule = new ConcurrentHashMap<>();
    private final LongAdder timeToSupersedeCount = new LongAdder();
    private final LongAdder timeToSupersedeMillis = new LongAdder();

    @Override
    public void recordSyntheticInjection(String objectType, String ruleId, String state) {
        syntheticInjections.increment();
    }

    @Override
    public void adjustSyntheticActive(String objectType, String ruleId, long delta) {
        if (delta == 0) {
            return;
        }
        activeByRule
                .computeIfAbsent(new RuleKey(objectType, ruleId), key -> new LongAdder())
                .add(delta);
    }

    @Override
    public void recordSyntheticSuperseded(String objectType, String ruleId, Duration timeToSupersede) {
        syntheticSuperseded.increment();
        if (timeToSupersede != null) {
            timeToSupersedeCount.increment();
            timeToSupersedeMillis.add(timeToSupersede.toMillis());
        }
    }

    @Override
    public void recordFanoutTruncation(String objectType, String counterName, int originalSize, int truncatedSize) {
        fanoutTruncations.increment();
    }

    @Override
    public void recordSeenStatesCapExceeded(String objectType, String attribute, int cap) {
        seenStatesCapExceeded.increment();
    }

    @Override
    public void recordPostingDedupHits(long hits) {
        if (hits > 0) {
            postingDedupHits.add(hits);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                syntheticInjections.sum(),
                syntheticSuperseded.sum(),
                fanoutTruncations.sum(),
                seenStatesCapExceeded.sum(),
                postingDedupHits.sum(),
                activeByRule,
                timeToSupersedeCount.sum(),
                timeToSupersedeMillis.sum());
    }

    public record Snapshot(
            long syntheticInjections,
            long syntheticSuperseded,
            long fanoutTruncations,
            long seenStatesCapExceeded,
            long postingDedupHits,
            Map<RuleKey, LongAdder> activeByRule,
            long timeToSupersedeCount,
            long timeToSupersedeMillis) {}

    public record RuleKey(String objectType, String ruleId) {}
}

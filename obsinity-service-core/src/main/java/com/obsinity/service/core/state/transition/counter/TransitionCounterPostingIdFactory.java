package com.obsinity.service.core.state.transition.counter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TransitionCounterPostingIdFactory {
    public String build(String eventId, TransitionCounterMetricKey metricKey, long delta, Instant eventTs) {
        String sign = delta >= 0 ? "+" : "-";
        long bucketStart = eventTs != null
                ? com.obsinity.service.core.counter.CounterGranularity.S5
                        .baseBucket()
                        .align(eventTs)
                        .toEpochMilli()
                : 0L;
        String seed = eventId + "|" + metricKey.dedupKey() + "|" + sign + "|" + bucketStart;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}

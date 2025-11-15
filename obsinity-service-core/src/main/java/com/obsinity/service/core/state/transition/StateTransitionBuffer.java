package com.obsinity.service.core.state.transition;

import com.obsinity.service.core.counter.CounterGranularity;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory buffer for state transition counts, modeled after counter buffers.
 */
@Component
public class StateTransitionBuffer {

    private final EnumMap<CounterGranularity, ConcurrentMap<Long, ConcurrentMap<TransitionKey, BufferedEntry>>>
            buffers = new EnumMap<>(CounterGranularity.class);

    public StateTransitionBuffer() {
        for (CounterGranularity granularity : CounterGranularity.values()) {
            buffers.put(granularity, new ConcurrentHashMap<>());
        }
    }

    public void increment(
            CounterGranularity granularity,
            long epoch,
            UUID serviceId,
            String objectType,
            String attribute,
            String fromState,
            String toState) {
        if (serviceId == null || objectType == null || attribute == null || fromState == null || toState == null) {
            return;
        }
        TransitionKey key = new TransitionKey(serviceId, objectType, attribute, fromState, toState);
        ConcurrentMap<Long, ConcurrentMap<TransitionKey, BufferedEntry>> granularityBuffer = buffers.get(granularity);
        ConcurrentMap<TransitionKey, BufferedEntry> epochMap =
                granularityBuffer.computeIfAbsent(epoch, k -> new ConcurrentHashMap<>());
        epochMap.compute(key, (k, existing) -> existing == null ? new BufferedEntry(key, 1) : existing.increment());
    }

    public ConcurrentMap<Long, ConcurrentMap<TransitionKey, BufferedEntry>> getBuffer(CounterGranularity granularity) {
        return buffers.get(granularity);
    }

    public void decrement(CounterGranularity granularity, long epoch, TransitionKey key, long value) {
        ConcurrentMap<Long, ConcurrentMap<TransitionKey, BufferedEntry>> granularityBuffer = buffers.get(granularity);
        ConcurrentMap<TransitionKey, BufferedEntry> epochMap = granularityBuffer.get(epoch);
        if (epochMap == null) {
            return;
        }
        epochMap.computeIfPresent(key, (k, existing) -> {
            long updated = Math.max(0, existing.count - value);
            return updated == 0 ? null : new BufferedEntry(existing.key, updated);
        });
        if (epochMap.isEmpty()) {
            granularityBuffer.remove(epoch, epochMap);
        }
    }

    public void cleanupOldEntries(CounterGranularity granularity) {
        ConcurrentMap<Long, ConcurrentMap<TransitionKey, BufferedEntry>> granularityBuffer = buffers.get(granularity);
        AtomicInteger removedKeys = new AtomicInteger();
        AtomicInteger removedEpochs = new AtomicInteger();

        granularityBuffer.forEach((epoch, keyMap) -> {
            keyMap.keySet().forEach(key -> keyMap.compute(key, (k, existing) -> {
                if (existing == null || existing.count <= 0) {
                    removedKeys.incrementAndGet();
                    return null;
                }
                return existing;
            }));
            if (keyMap.isEmpty()) {
                granularityBuffer.remove(epoch);
                removedEpochs.incrementAndGet();
            }
        });
    }

    @Value
    public static class TransitionKey {
        UUID serviceId;
        String objectType;
        String attribute;
        String fromState;
        String toState;
    }

    @Value
    public static class BufferedEntry {
        TransitionKey key;
        long count;

        BufferedEntry increment() {
            return new BufferedEntry(key, count + 1);
        }
    }
}

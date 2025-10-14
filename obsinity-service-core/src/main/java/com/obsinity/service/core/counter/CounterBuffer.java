package com.obsinity.service.core.counter;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CounterBuffer {

    private final CounterHashService hashService;

    public record BufferedCounterEntry(
            UUID counterConfigId, UUID eventTypeId, String keyHash, Map<String, String> keyData, long counter) {}

    private final EnumMap<CounterGranularity, ConcurrentMap<Long, ConcurrentMap<String, BufferedCounterEntry>>>
            buffers = new EnumMap<>(CounterGranularity.class);

    {
        for (CounterGranularity granularity : CounterGranularity.values()) {
            buffers.put(granularity, new ConcurrentHashMap<>());
        }
    }

    public ConcurrentMap<Long, ConcurrentMap<String, BufferedCounterEntry>> getBuffer(CounterGranularity granularity) {
        return buffers.get(granularity);
    }

    public void increment(
            CounterGranularity granularity,
            long epoch,
            UUID counterConfigId,
            UUID eventTypeId,
            String keyHash,
            long value,
            Map<String, String> keyData) {
        hashService.getOrCreateHash(keyData);
        var epochMap = buffers.get(granularity).computeIfAbsent(epoch, k -> new ConcurrentHashMap<>());
        epochMap.compute(keyHash, (k, existing) -> {
            if (existing == null) {
                return new BufferedCounterEntry(counterConfigId, eventTypeId, keyHash, keyData, value);
            }
            return new BufferedCounterEntry(
                    counterConfigId, eventTypeId, keyHash, existing.keyData(), existing.counter() + value);
        });
    }

    public void decrement(CounterGranularity granularity, long epoch, String keyHash, long value) {
        ConcurrentMap<String, BufferedCounterEntry> epochMap =
                buffers.get(granularity).get(epoch);
        if (epochMap == null) {
            return;
        }
        epochMap.computeIfPresent(keyHash, (k, existing) -> {
            long updated = Math.max(0, existing.counter() - value);
            if (updated == 0) {
                return null;
            }
            return new BufferedCounterEntry(
                    existing.counterConfigId(),
                    existing.eventTypeId(),
                    existing.keyHash(),
                    existing.keyData(),
                    updated);
        });
        if (epochMap.isEmpty()) {
            buffers.get(granularity).remove(epoch, epochMap);
        }
    }

    public void cleanupOldEntries(CounterGranularity granularity) {
        ConcurrentMap<Long, ConcurrentMap<String, BufferedCounterEntry>> granularityBuffer = buffers.get(granularity);
        AtomicInteger removedKeys = new AtomicInteger();
        AtomicInteger removedEpochs = new AtomicInteger();

        granularityBuffer.forEach((epoch, keyMap) -> {
            keyMap.keySet()
                    .forEach(key -> keyMap.compute(key, (k, existing) -> {
                        if (existing == null) {
                            return null;
                        }
                        if (existing.counter() == 0) {
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

        String remaining = granularityBuffer.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> String.format(
                        "%s=%d",
                        Instant.ofEpochSecond(entry.getKey()).toString(),
                        entry.getValue() != null ? entry.getValue().size() : 0))
                .collect(Collectors.joining(", "));

        log.info(
                "Buffer cleanup granularity={} epochsRemoved={} keysRemoved={} remainingEpochs={} [{}]",
                granularity,
                removedEpochs.get(),
                removedKeys.get(),
                granularityBuffer.size(),
                remaining);
    }
}

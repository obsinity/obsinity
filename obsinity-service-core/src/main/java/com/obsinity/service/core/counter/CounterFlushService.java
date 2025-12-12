package com.obsinity.service.core.counter;

import com.obsinity.service.core.config.PipelineProperties;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CounterFlushService {

    private final CounterBuffer buffer;
    private final CounterPersistExecutor persistExecutor;
    private final PipelineProperties pipelineProperties;

    private int maxBatchSize;

    private final Object flushLock = new Object();

    @PostConstruct
    void configureBatchSize() {
        this.maxBatchSize = pipelineProperties.getCounters().getFlush().getMaxBatchSize();
    }

    @Scheduled(fixedRateString = "${obsinity.counters.flush.rate.s5:5000}")
    public void flushScheduled() {
        for (CounterGranularity granularity : CounterGranularity.values()) {
            flushGranularity(granularity);
        }
    }

    public void flushAndWait(CounterGranularity granularity) {
        flushGranularity(granularity);
    }

    /** Force flushing all pending epochs regardless of cutoff. */
    public void flushAllPending(CounterGranularity granularity) {
        synchronized (flushLock) {
            ConcurrentMap<Long, ConcurrentMap<String, CounterBuffer.BufferedCounterEntry>> bucket =
                    buffer.getBuffer(granularity);
            for (Map.Entry<Long, ConcurrentMap<String, CounterBuffer.BufferedCounterEntry>> entry : bucket.entrySet()) {
                flushEpoch(granularity, entry.getKey(), entry.getValue());
            }
        }
        persistExecutor.waitForDrain();
    }

    private void flushGranularity(CounterGranularity granularity) {
        synchronized (flushLock) {
            try {
                ConcurrentMap<Long, ConcurrentMap<String, CounterBuffer.BufferedCounterEntry>> bucket =
                        buffer.getBuffer(granularity);
                long now = Instant.now().getEpochSecond();
                long bucketSeconds = granularity.duration().toSeconds();
                long cutoff = (now / bucketSeconds) * bucketSeconds - bucketSeconds;

                for (Map.Entry<Long, ConcurrentMap<String, CounterBuffer.BufferedCounterEntry>> entry :
                        bucket.entrySet()) {
                    long epoch = entry.getKey();
                    if (epoch > cutoff) {
                        continue;
                    }
                    flushEpoch(granularity, epoch, entry.getValue());
                }

                buffer.cleanupOldEntries(granularity);
            } catch (Exception ex) {
                log.error("Failed to flush counters for granularity {}", granularity, ex);
            }
        }
    }

    private void flushEpoch(
            CounterGranularity granularity, long epoch, Map<String, CounterBuffer.BufferedCounterEntry> keyCounts) {
        if (keyCounts == null || keyCounts.isEmpty()) {
            return;
        }
        Instant ts = Instant.ofEpochSecond(epoch);
        List<CounterPersistService.BatchItem> batch = new ArrayList<>();
        for (CounterBuffer.BufferedCounterEntry entry : keyCounts.values()) {
            if (entry.counter() <= 0) {
                continue;
            }
            String keyDataJson = KeyDataCanonicalizer.toJson(entry.keyData());
            batch.add(new CounterPersistService.BatchItem(
                    ts,
                    entry.counterConfigId(),
                    entry.eventTypeId(),
                    entry.keyHash(),
                    entry.keyData(),
                    keyDataJson,
                    entry.counter()));
        }
        if (batch.isEmpty()) {
            return;
        }

        int total = batch.size();
        for (int i = 0; i < total; i += maxBatchSize) {
            int toIndex = Math.min(i + maxBatchSize, total);
            List<CounterPersistService.BatchItem> chunk = new ArrayList<>(batch.subList(i, toIndex));
            persistExecutor.submit(new CounterPersistExecutor.Job(granularity, epoch, chunk));
        }
    }
}

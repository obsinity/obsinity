package com.obsinity.service.core.histogram;

import com.obsinity.service.core.counter.CounterGranularity;
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
public class HistogramFlushService {

    private final HistogramBuffer buffer;
    private final HistogramPersistExecutor persistExecutor;

    @Scheduled(fixedRateString = "${obsinity.histograms.flush.rate:5000}")
    public void flushFiveSecond() {
        flushGranularity(CounterGranularity.S5);
    }

    private void flushGranularity(CounterGranularity granularity) {
        ConcurrentMap<Long, ConcurrentMap<String, HistogramBuffer.BufferedHistogramEntry>> granularityBuffer =
                buffer.getBuffer(granularity);
        if (granularityBuffer == null || granularityBuffer.isEmpty()) {
            return;
        }

        long now = Instant.now().getEpochSecond();
        long bucketSeconds = granularity.duration().toSeconds();
        long cutoff = (now / bucketSeconds) * bucketSeconds - bucketSeconds;

        List<Long> epochsToFlush = new ArrayList<>();
        granularityBuffer.forEach((epoch, ignored) -> {
            if (epoch <= cutoff) {
                epochsToFlush.add(epoch);
            }
        });

        int maxEpochsPerRun = 5000;
        int processed = 0;
        for (Long epoch : epochsToFlush) {
            if (processed >= maxEpochsPerRun) {
                break;
            }
            Map<String, HistogramBuffer.BufferedHistogramEntry> entries = buffer.removeEpoch(granularity, epoch);
            if (entries.isEmpty()) {
                continue;
            }
            persistExecutor.submit(new HistogramPersistExecutor.Job(granularity, epoch, entries.values()));
            processed++;
        }

        buffer.cleanupOldEntries(granularity);
    }
}

package com.obsinity.service.core.histogram;

import com.obsinity.service.core.config.PipelineProperties;
import com.obsinity.service.core.counter.CounterGranularity;
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
public class HistogramFlushService {

    private final HistogramBuffer buffer;
    private final HistogramPersistExecutor persistExecutor;
    private final PipelineProperties pipelineProperties;
    private int maxEpochsPerRun;

    @PostConstruct
    void configure() {
        this.maxEpochsPerRun = pipelineProperties.getHistograms().getFlush().getMaxBatchSize();
    }

    @Scheduled(fixedRateString = "${obsinity.histograms.flush.rate.s5:5000}")
    public void flushScheduled() {
        for (CounterGranularity granularity : CounterGranularity.values()) {
            flushGranularity(granularity);
        }
    }

    public void flushAndWait() {
        flushGranularity(CounterGranularity.S5);
        persistExecutor.waitForDrain();
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

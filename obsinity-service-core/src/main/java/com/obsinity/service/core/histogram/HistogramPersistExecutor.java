package com.obsinity.service.core.histogram;

import com.obsinity.service.core.counter.CounterGranularity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class HistogramPersistExecutor {

    private final HistogramPersistService persistService;

    @Value("${obsinity.histograms.persist.queue-capacity:20000}")
    private int queueCapacity;

    @Value("${obsinity.histograms.persist.workers:10}")
    private int workerCount;

    private BlockingQueue<PersistJob> queue;
    private ExecutorService executor;

    @PostConstruct
    void start() {
        queue = new ArrayBlockingQueue<>(queueCapacity);
        executor = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::drainLoop);
        }
        log.info("Histogram persist executor started workers={}, queueCapacity={}", workerCount, queueCapacity);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    public void submit(Job job) {
        try {
            queue.put(new PersistJob(job.granularity(), job.epoch(), job.entries()));
            log.info(
                    "Histogram persist queue depth={}/{}", queue.size(), queueCapacity);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while submitting histogram persist job", ie);
        }
    }

    private void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                PersistJob job = queue.take();
                persistService.persist(job.granularity(), job.epoch(), job.entries());
                long totalSamples = job.entries().stream()
                        .mapToLong(HistogramBuffer.BufferedHistogramEntry::getSamples)
                        .sum();
                log.info(
                        "Histogram persist complete granularity={} epoch={} keys={} samples={}",
                        job.granularity(),
                        Instant.ofEpochSecond(job.epoch()),
                        job.entries().size(),
                        totalSamples);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.error("Histogram persist worker failed", ex);
        }
    }

    public record Job(
            CounterGranularity granularity, long epoch, Collection<HistogramBuffer.BufferedHistogramEntry> entries) {}

    private record PersistJob(
            CounterGranularity granularity, long epoch, Collection<HistogramBuffer.BufferedHistogramEntry> entries) {}
}

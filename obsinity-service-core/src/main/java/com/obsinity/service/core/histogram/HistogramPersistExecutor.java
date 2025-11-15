package com.obsinity.service.core.histogram;

import com.obsinity.service.core.config.PipelineProperties;
import com.obsinity.service.core.counter.CounterGranularity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class HistogramPersistExecutor {

    private final HistogramPersistService persistService;
    private final PipelineProperties pipelineProperties;

    private int queueCapacity;
    private int workerCount;

    private BlockingQueue<PersistJob> queue;
    private ExecutorService executor;
    private final AtomicInteger activeJobs = new AtomicInteger();

    @PostConstruct
    void start() {
        PipelineProperties.Persist persist = pipelineProperties.getHistograms().getPersist();
        this.queueCapacity = persist.getQueueCapacity();
        this.workerCount = persist.getWorkers();
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
            log.info("Histogram persist queue depth={}/{}", queue.size(), queueCapacity);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while submitting histogram persist job", ie);
        }
    }

    private void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                PersistJob job = queue.take();
                activeJobs.incrementAndGet();
                try {
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
                    log.info("Histogram persist queue depth after drain={}/{}", queue.size(), queueCapacity);
                } finally {
                    activeJobs.decrementAndGet();
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.error("Histogram persist worker failed", ex);
        }
    }

    public void waitForDrain() {
        while (!queue.isEmpty() || activeJobs.get() > 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public record Job(
            CounterGranularity granularity, long epoch, Collection<HistogramBuffer.BufferedHistogramEntry> entries) {}

    private record PersistJob(
            CounterGranularity granularity, long epoch, Collection<HistogramBuffer.BufferedHistogramEntry> entries) {}
}

package com.obsinity.service.core.counter;

import com.obsinity.service.core.counter.CounterPersistService.BatchItem;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
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
public class CounterPersistExecutor {

    private final CounterPersistService persistService;
    private final CounterBuffer buffer;

    @Value("${obsinity.counters.persist.queue-capacity:20000}")
    private int queueCapacity;

    @Value("${obsinity.counters.persist.workers:10}")
    private int workerCount;

    private BlockingQueue<Job> queue;
    private ExecutorService executor;

    @PostConstruct
    void start() {
        init(queueCapacity, workerCount);
    }

    void init(int capacity, int workers) {
        queue = new ArrayBlockingQueue<>(capacity);
        executor = Executors.newFixedThreadPool(workers);
        for (int i = 0; i < workers; i++) {
            executor.submit(this::drainLoop);
        }
        log.info("Counter persist executor started workers={}, queueCapacity={}", workers, capacity);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    public void submit(Job job) {
        try {
            queue.put(job);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while submitting counter persist job", ie);
        }
    }

    void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Job job = queue.take();
                persistService.persistBatch(job.granularity(), job.batch());
                for (BatchItem item : job.batch()) {
                    buffer.decrement(job.granularity(), job.epoch(), item.keyHash(), item.delta());
                }
                long total = job.batch().stream().mapToLong(BatchItem::delta).sum();
                log.info(
                        "Counter persist complete granularity={} epoch={} keys={} totalDelta={}",
                        job.granularity(),
                        Instant.ofEpochSecond(job.epoch()),
                        job.batch().size(),
                        total);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.error("Counter persist worker failed", ex);
        }
    }

    public record Job(CounterGranularity granularity, long epoch, List<BatchItem> batch) {}
}

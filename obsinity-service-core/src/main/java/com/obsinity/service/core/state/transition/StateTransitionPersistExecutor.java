package com.obsinity.service.core.state.transition;

import com.obsinity.service.core.counter.CounterGranularity;
import com.obsinity.service.core.state.transition.StateTransitionBuffer.TransitionKey;
import com.obsinity.service.core.state.transition.StateTransitionPersistService.BatchItem;
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
public class StateTransitionPersistExecutor {

    private final StateTransitionPersistService persistService;
    private final StateTransitionBuffer buffer;

    @Value("${obsinity.stateTransitions.persist.queue-capacity:5000}")
    private int queueCapacity;

    @Value("${obsinity.stateTransitions.persist.workers:4}")
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
        log.info("State transition persist executor started workers={}, queueCapacity={}", workers, capacity);
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
            throw new IllegalStateException("Interrupted while submitting transition persist job", ie);
        }
    }

    void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Job job = queue.take();
                persistService.persistBatch(job.granularity(), job.batch());
                for (BatchItem item : job.batch()) {
                    TransitionKey key = new TransitionKey(
                            item.serviceId(), item.objectType(), item.attribute(), item.fromState(), item.toState());
                    buffer.decrement(job.granularity(), job.epoch(), key, item.count());
                }
                long total = job.batch().stream().mapToLong(BatchItem::count).sum();
                log.info(
                        "State transition persist complete granularity={} epoch={} entries={} totalDelta={}",
                        job.granularity(),
                        Instant.ofEpochSecond(job.epoch()),
                        job.batch().size(),
                        total);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.error("State transition persist worker failed", ex);
        }
    }

    public void waitForDrain() {
        while (!queue.isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public record Job(CounterGranularity granularity, long epoch, List<BatchItem> batch) {}
}

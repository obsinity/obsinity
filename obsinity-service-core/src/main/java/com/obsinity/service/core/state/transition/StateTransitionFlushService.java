package com.obsinity.service.core.state.transition;

import com.obsinity.service.core.counter.CounterGranularity;
import com.obsinity.service.core.state.transition.StateTransitionBuffer.BufferedEntry;
import com.obsinity.service.core.state.transition.StateTransitionBuffer.TransitionKey;
import com.obsinity.service.core.state.transition.StateTransitionPersistService.BatchItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StateTransitionFlushService {

    private final StateTransitionBuffer buffer;
    private final StateTransitionPersistExecutor persistExecutor;

    @Value("${obsinity.stateTransitions.flush.max-batch-size:5000}")
    private int maxBatchSize;

    private final Object flushLock = new Object();

    @Scheduled(fixedRateString = "${obsinity.stateTransitions.flush.rate.s5:5000}")
    public void flushFiveSecond() {
        flushGranularity(CounterGranularity.S5);
    }

    public void flushAndWait(CounterGranularity granularity) {
        flushGranularity(granularity);
        persistExecutor.waitForDrain();
    }

    void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    private void flushGranularity(CounterGranularity granularity) {
        synchronized (flushLock) {
            try {
                ConcurrentMap<Long, ConcurrentMap<TransitionKey, BufferedEntry>> bucket = buffer.getBuffer(granularity);
                long now = Instant.now().getEpochSecond();
                long bucketSeconds = granularity.duration().toSeconds();
                long cutoff = (now / bucketSeconds) * bucketSeconds - bucketSeconds;

                for (Map.Entry<Long, ConcurrentMap<TransitionKey, BufferedEntry>> entry : bucket.entrySet()) {
                    long epoch = entry.getKey();
                    if (epoch > cutoff) {
                        continue;
                    }
                    flushEpoch(granularity, epoch, entry.getValue());
                }

                buffer.cleanupOldEntries(granularity);
            } catch (Exception ex) {
                log.error("Failed to flush state transitions for granularity {}", granularity, ex);
            }
        }
    }

    private void flushEpoch(CounterGranularity granularity, long epoch, Map<TransitionKey, BufferedEntry> transitions) {
        if (transitions == null || transitions.isEmpty()) {
            return;
        }
        Instant ts = Instant.ofEpochSecond(epoch);
        List<BatchItem> batch = new ArrayList<>();
        for (BufferedEntry entry : transitions.values()) {
            if (entry.getCount() <= 0) {
                continue;
            }
            TransitionKey key = entry.getKey();
            batch.add(new BatchItem(
                    ts,
                    key.getServiceId(),
                    key.getObjectType(),
                    key.getAttribute(),
                    key.getFromState(),
                    key.getToState(),
                    entry.getCount()));
        }

        if (batch.isEmpty()) {
            return;
        }

        int total = batch.size();
        for (int i = 0; i < total; i += maxBatchSize) {
            int toIndex = Math.min(i + maxBatchSize, total);
            List<BatchItem> chunk = new ArrayList<>(batch.subList(i, toIndex));
            persistExecutor.submit(new StateTransitionPersistExecutor.Job(granularity, epoch, chunk));
        }
    }
}

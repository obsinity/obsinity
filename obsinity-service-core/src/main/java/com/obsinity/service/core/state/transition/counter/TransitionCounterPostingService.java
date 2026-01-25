package com.obsinity.service.core.state.transition.counter;

import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.CounterGranularity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class TransitionCounterPostingService implements TransitionCounterPostingSink {
    static final String OPEN_STATE = "(open)";

    private final TransitionCounterPostingIdRepository postingIdRepository;
    private final TransitionCounterRollupRepository rollupRepository;
    private final TransactionTemplate txTemplate;

    private final CounterGranularity baseGranularity = CounterGranularity.S5;

    @Override
    public void post(TransitionCounterMetricKey key, Instant timestamp, long delta, String postingId) {
        if (key == null || timestamp == null || postingId == null || delta == 0) {
            return;
        }
        postAll(List.of(new TransitionCounterPosting(key, timestamp, delta, postingId)));
    }

    public void postAll(List<TransitionCounterPosting> postings) {
        if (postings == null || postings.isEmpty()) {
            return;
        }
        txTemplate.execute(status -> {
            List<TransitionCounterPosting> fresh = postingIdRepository.filterNew(postings);
            if (fresh.isEmpty()) {
                return null;
            }
            EnumSet<CounterBucket> buckets = baseGranularity.materialisedBuckets();
            for (CounterBucket bucket : buckets) {
                List<TransitionCounterRollupRepository.RollupRow> rows = alignAndAggregate(fresh, bucket);
                rollupRepository.applyBatch(bucket, rows);
            }
            return null;
        });
    }

    private List<TransitionCounterRollupRepository.RollupRow> alignAndAggregate(
            List<TransitionCounterPosting> postings, CounterBucket bucket) {
        if (postings.isEmpty()) {
            return List.of();
        }
        Map<RollupKey, Long> aggregated = new LinkedHashMap<>();
        for (TransitionCounterPosting posting : postings) {
            TransitionCounterMetricKey key = posting.key();
            Instant aligned = bucket.align(posting.timestamp());
            RollupKey rollupKey = new RollupKey(
                    aligned,
                    key.serviceId(),
                    key.objectType(),
                    key.attribute(),
                    key.counterName(),
                    key.storageFromState(),
                    key.storageToState());
            aggregated.merge(rollupKey, posting.delta(), Long::sum);
        }
        List<TransitionCounterRollupRepository.RollupRow> rows = new ArrayList<>(aggregated.size());
        for (Map.Entry<RollupKey, Long> entry : aggregated.entrySet()) {
            RollupKey key = entry.getKey();
            rows.add(new TransitionCounterRollupRepository.RollupRow(
                    key.timestamp,
                    key.serviceId,
                    key.objectType,
                    key.attribute,
                    key.counterName,
                    key.fromState,
                    key.toState,
                    entry.getValue()));
        }
        return rows;
    }

    private static final class RollupKey {
        private final Instant timestamp;
        private final java.util.UUID serviceId;
        private final String objectType;
        private final String attribute;
        private final String counterName;
        private final String fromState;
        private final String toState;

        private RollupKey(
                Instant timestamp,
                java.util.UUID serviceId,
                String objectType,
                String attribute,
                String counterName,
                String fromState,
                String toState) {
            this.timestamp = timestamp;
            this.serviceId = serviceId;
            this.objectType = objectType;
            this.attribute = attribute;
            this.counterName = counterName;
            this.fromState = fromState;
            this.toState = toState;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RollupKey key)) return false;
            return timestamp.equals(key.timestamp)
                    && serviceId.equals(key.serviceId)
                    && objectType.equals(key.objectType)
                    && attribute.equals(key.attribute)
                    && counterName.equals(key.counterName)
                    && fromState.equals(key.fromState)
                    && toState.equals(key.toState);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(timestamp, serviceId, objectType, attribute, counterName, fromState, toState);
        }
    }
}

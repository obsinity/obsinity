package com.obsinity.service.core.state.timeseries;

import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.repo.ObjectStateCountRepository;
import com.obsinity.service.core.repo.StateCountTimeseriesRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StateCountTimeseriesJob {

    private static final CounterBucket SNAPSHOT_BUCKET = CounterBucket.M1;

    private final ObjectStateCountRepository stateCountRepository;
    private final StateCountTimeseriesRepository timeseriesRepository;
    private final Clock clock;

    @Value("${obsinity.stateCounts.timeseries.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedRateString = "${obsinity.stateCounts.timeseries.snapshotRateMillis:60000}")
    public void snapshotCounts() {
        if (!enabled) {
            return;
        }
        Instant aligned = SNAPSHOT_BUCKET.align(Instant.now(clock));
        List<ObjectStateCountRepository.StateCountSnapshot> snapshots = stateCountRepository.snapshotAll();
        if (snapshots.isEmpty()) {
            return;
        }
        timeseriesRepository.upsertBatch(aligned, SNAPSHOT_BUCKET, snapshots);
        if (log.isDebugEnabled()) {
            log.debug("Recorded {} state count snapshots for {}", snapshots.size(), aligned);
        }
    }
}

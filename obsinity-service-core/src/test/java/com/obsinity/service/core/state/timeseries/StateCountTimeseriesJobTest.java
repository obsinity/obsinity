package com.obsinity.service.core.state.timeseries;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.obsinity.service.core.repo.ObjectStateCountRepository;
import com.obsinity.service.core.repo.ObjectStateCountRepository.StateCountSnapshot;
import com.obsinity.service.core.repo.StateCountTimeseriesRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class StateCountTimeseriesJobTest {

    @Mock
    private ObjectStateCountRepository stateCountRepository;

    @Mock
    private StateCountTimeseriesRepository timeseriesRepository;

    private StateCountTimeseriesJob job;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:07:30Z"), ZoneOffset.UTC);
        job = new StateCountTimeseriesJob(stateCountRepository, timeseriesRepository, fixedClock);
        setBoolean(job, "enabled", true);
    }

    @Test
    void snapshotCountsPersistsRows() {
        List<StateCountSnapshot> snapshots =
                List.of(new StateCountSnapshot(UUID.randomUUID(), "UserProfile", "user.status", "ACTIVE", 42));
        when(stateCountRepository.snapshotAll()).thenReturn(snapshots);

        job.snapshotCounts();

        InOrder order = inOrder(timeseriesRepository);
        order.verify(timeseriesRepository)
                .upsertBatch(
                        Instant.parse("2025-01-01T00:07:00Z"),
                        com.obsinity.service.core.counter.CounterBucket.M1,
                        snapshots);
        order.verify(timeseriesRepository)
                .upsertBatch(
                        Instant.parse("2025-01-01T00:05:00Z"),
                        com.obsinity.service.core.counter.CounterBucket.M5,
                        snapshots);
        order.verify(timeseriesRepository)
                .upsertBatch(
                        Instant.parse("2025-01-01T00:00:00Z"),
                        com.obsinity.service.core.counter.CounterBucket.H1,
                        snapshots);
        order.verify(timeseriesRepository)
                .upsertBatch(
                        Instant.parse("2025-01-01T00:00:00Z"),
                        com.obsinity.service.core.counter.CounterBucket.D1,
                        snapshots);
    }

    @Test
    void snapshotCountsSkipsWhenDisabled() {
        setBoolean(job, "enabled", false);
        job.snapshotCounts();
        verifyNoInteractions(stateCountRepository, timeseriesRepository);
    }

    private static void setBoolean(StateCountTimeseriesJob job, String fieldName, boolean value) {
        try {
            Field field = StateCountTimeseriesJob.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(job, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

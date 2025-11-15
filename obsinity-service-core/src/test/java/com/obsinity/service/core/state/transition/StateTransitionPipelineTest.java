package com.obsinity.service.core.state.transition;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.service.core.counter.CounterGranularity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class StateTransitionPipelineTest {

    @Test
    void bufferFlushAndPersist() throws Exception {
        StateTransitionBuffer buffer = new StateTransitionBuffer();
        RecordingPersistService persistService = new RecordingPersistService();
        StateTransitionPersistExecutor executor = new StateTransitionPersistExecutor(persistService, buffer);
        executor.init(100, 1);
        StateTransitionFlushService flushService = new StateTransitionFlushService(buffer, executor);
        flushService.setMaxBatchSize(10);

        UUID serviceId = UUID.randomUUID();
        Instant occurred = Instant.parse("2025-01-01T00:00:02Z");
        long epoch = CounterGranularity.S5.baseBucket().align(occurred).getEpochSecond();
        buffer.increment(CounterGranularity.S5, epoch, serviceId, "UserProfile", "user.status", "ACTIVE", "BLOCKED");

        flushService.flushAndWait(CounterGranularity.S5);

        assertThat(persistService.captured).isNotEmpty();
        StateTransitionPersistService.BatchItem item = persistService.captured.get(0);
        assertThat(item.fromState()).isEqualTo("ACTIVE");
        assertThat(item.toState()).isEqualTo("BLOCKED");
        assertThat(buffer.getBuffer(CounterGranularity.S5).get(epoch)).isNullOrEmpty();
    }

    private static final class RecordingPersistService extends StateTransitionPersistService {
        private final List<BatchItem> captured = new ArrayList<>();

        RecordingPersistService() {
            super(Mockito.mock(JdbcTemplate.class));
        }

        @Override
        public void persistBatch(CounterGranularity baseGranularity, List<BatchItem> batch) {
            captured.addAll(batch);
        }
    }
}

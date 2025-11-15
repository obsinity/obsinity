package com.obsinity.service.core.state.transition;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.service.core.counter.CounterGranularity;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StateTransitionBufferTest {

    @Test
    void incrementAndCleanup() {
        StateTransitionBuffer buffer = new StateTransitionBuffer();
        UUID serviceId = UUID.randomUUID();

        buffer.increment(
                CounterGranularity.S5, 100L, serviceId, "UserProfile", "status", "ACTIVE", "BLOCKED");
        buffer.increment(
                CounterGranularity.S5, 100L, serviceId, "UserProfile", "status", "ACTIVE", "BLOCKED");

        Map<StateTransitionBuffer.TransitionKey, StateTransitionBuffer.BufferedEntry> epoch =
                buffer.getBuffer(CounterGranularity.S5).get(100L);
        assertThat(epoch).isNotNull();
        assertThat(epoch.values().iterator().next().getCount()).isEqualTo(2);

        StateTransitionBuffer.TransitionKey key = epoch.keySet().iterator().next();
        buffer.decrement(CounterGranularity.S5, 100L, key, 1);
        assertThat(epoch.get(key).getCount()).isEqualTo(1);

        buffer.cleanupOldEntries(CounterGranularity.S5);
        assertThat(buffer.getBuffer(CounterGranularity.S5)).isNotEmpty();
        buffer.decrement(CounterGranularity.S5, 100L, key, 1);
        buffer.cleanupOldEntries(CounterGranularity.S5);
        assertThat(buffer.getBuffer(CounterGranularity.S5).get(100L)).isNull();
    }
}

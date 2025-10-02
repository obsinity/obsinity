package com.obsinity.collection.core.processor;

import static org.junit.jupiter.api.Assertions.*;

import com.obsinity.collection.core.dispatch.AsyncDispatchBus;
import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.telemetry.model.TelemetryEvent;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DefaultTelemetryProcessorTest {

    private final TelemetryHandlerRegistry registry = new TelemetryHandlerRegistry();
    private final AsyncDispatchBus bus = new AsyncDispatchBus(registry);

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    void onFlowFailed_propagates_throwable_to_holder() throws Exception {
        BlockingQueue<TelemetryEvent> seen = new ArrayBlockingQueue<>(4);
        registry.register(seen::offer);

        var support = new com.obsinity.telemetry.processor.TelemetryProcessorSupport();
        var proc = new DefaultTelemetryProcessor(bus, support);

        proc.onFlowStarted("demo.flow", Map.of(), Map.of());

        RuntimeException err = new RuntimeException("boom");
        proc.onFlowFailed("demo.flow", err, Map.of(), Map.of());

        TelemetryEvent h = seen.poll(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(h);
        assertEquals("FAILED", h.eventContext().get("lifecycle"));
        assertSame(err, h.throwable());
        assertEquals("RuntimeException", h.attributes().map().get("error"));
    }
}

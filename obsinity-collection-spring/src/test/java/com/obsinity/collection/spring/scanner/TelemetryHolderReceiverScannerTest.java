package com.obsinity.collection.spring.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.FlowException;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.RequiredAttributes;
import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.telemetry.model.TelemetryEvent;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelemetryHolderReceiverScannerTest {

    @EventReceiver
    static class MyReceivers {
        static final AtomicInteger failureAny = new AtomicInteger();
        static final AtomicInteger failureWithAttrs = new AtomicInteger();
        static final AtomicInteger failureWithThrowableOnly = new AtomicInteger();
        static volatile Throwable lastThrowable;
        static final AtomicInteger completedWithThrowable = new AtomicInteger();

        @OnFlowFailure
        public void onFailureAny(TelemetryEvent h) {
            failureAny.incrementAndGet();
        }

        @OnFlowFailure
        @RequiredAttributes({"order.id", "error.code"})
        public void onFailureWithAttrs(TelemetryEvent h) {
            failureWithAttrs.incrementAndGet();
        }

        @OnFlowFailure
        public void onFailureThrowable(Throwable t) {
            lastThrowable = t;
            failureWithThrowableOnly.incrementAndGet();
        }

        @OnFlowFailure
        public void onFailureThrowableRoot(@FlowException(FlowException.Source.ROOT) Throwable t) {
            // no-op; verifies parameter can be annotated
        }

        // Invalid signature for COMPLETED (Throwable params only allowed for failures); should be ignored
        @OnFlowCompleted
        public void onCompletedThrowable(Throwable t) {
            completedWithThrowable.incrementAndGet();
        }
    }

    private TelemetryHandlerRegistry registry;
    private TelemetryHolderReceiverScanner scanner;

    @BeforeEach
    void setUp() {
        registry = new TelemetryHandlerRegistry();
        scanner = new TelemetryHolderReceiverScanner(registry);
        // simulate Spring initialization
        scanner.postProcessAfterInitialization(new MyReceivers(), "myReceivers");

        // reset counters for isolation
        MyReceivers.failureAny.set(0);
        MyReceivers.failureWithAttrs.set(0);
        MyReceivers.failureWithThrowableOnly.set(0);
        MyReceivers.lastThrowable = null;
        MyReceivers.completedWithThrowable.set(0);
    }

    @Test
    void invokes_failure_handlers_when_failed_and_required_attrs_present() throws Exception {
        TelemetryEvent h = TelemetryEvent.builder()
                .name("demo.flow")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "FAILED");
        h.attributes().put("order.id", "123");
        h.attributes().put("error.code", "E42");

        for (var handler : registry.handlers()) {
            handler.handle(h);
        }

        assertThat(MyReceivers.failureAny.get()).isEqualTo(1);
        assertThat(MyReceivers.failureWithAttrs.get()).isEqualTo(1);
        assertThat(MyReceivers.failureWithThrowableOnly.get()).isEqualTo(1);
        assertThat(MyReceivers.lastThrowable).isNull();
    }

    @Test
    void skips_required_attrs_handler_when_attr_missing() throws Exception {
        TelemetryEvent h = TelemetryEvent.builder()
                .name("demo.flow")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "FAILED");
        h.attributes().put("order.id", "123");
        // missing error.code

        for (var handler : registry.handlers()) {
            handler.handle(h);
        }

        assertThat(MyReceivers.failureAny.get()).isEqualTo(1);
        assertThat(MyReceivers.failureWithAttrs.get()).isEqualTo(0);
        assertThat(MyReceivers.failureWithThrowableOnly.get()).isEqualTo(1);
    }

    @Test
    void does_not_invoke_failure_handlers_when_not_failed() throws Exception {
        TelemetryEvent h = TelemetryEvent.builder()
                .name("demo.flow")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "COMPLETED");
        h.attributes().put("order.id", "123");
        h.attributes().put("error.code", "E42");

        for (var handler : registry.handlers()) {
            handler.handle(h);
        }

        assertThat(MyReceivers.failureAny.get()).isEqualTo(0);
        assertThat(MyReceivers.failureWithAttrs.get()).isEqualTo(0);
        assertThat(MyReceivers.failureWithThrowableOnly.get()).isEqualTo(0);
        assertThat(MyReceivers.completedWithThrowable.get()).isEqualTo(0);
    }

    @Test
    void does_not_register_completed_handler_with_throwable_param() throws Exception {
        TelemetryEvent h = TelemetryEvent.builder()
                .name("demo.flow")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "COMPLETED");
        h.setThrowable(new RuntimeException("should not bind for completed"));

        for (var handler : registry.handlers()) {
            handler.handle(h);
        }

        assertThat(MyReceivers.completedWithThrowable.get()).isEqualTo(0);
    }

    @Test
    void binds_throwable_parameter_when_present() throws Exception {
        TelemetryEvent h = TelemetryEvent.builder()
                .name("demo.flow")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "FAILED");
        RuntimeException root = new RuntimeException("root");
        RuntimeException boom = new RuntimeException("boom", root);
        h.setThrowable(boom);

        for (var handler : registry.handlers()) {
            handler.handle(h);
        }

        assertThat(MyReceivers.failureWithThrowableOnly.get()).isGreaterThanOrEqualTo(1);
        assertThat(MyReceivers.lastThrowable).isSameAs(boom);
    }
}

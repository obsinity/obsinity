package com.obsinity.collection.spring.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.api.annotations.FlowSink;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowScope;
import com.obsinity.collection.api.annotations.OnFlowSuccess;
import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.telemetry.model.FlowEvent;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultipleScopeAndSuccessTest {

    @FlowSink
    @OnFlowScope("orders.")
    @OnFlowScope("payments.")
    static class OrdersOrPayments {
        static final AtomicInteger successOrders = new AtomicInteger();
        static final AtomicInteger successPayments = new AtomicInteger();
        static final AtomicInteger completedOthers = new AtomicInteger();

        @OnFlowSuccess
        public void orders(FlowEvent h) {
            if (h.name().equals("orders.create")) successOrders.incrementAndGet();
        }

        @OnFlowSuccess
        public void payments(FlowEvent h) {
            if (h.name().equals("payments.charge")) successPayments.incrementAndGet();
        }

        // Sanity: completed without success filter still fires; used to guard no extra matches
        @OnFlowCompleted
        public void completed(FlowEvent h) {
            if (!h.name().startsWith("orders.") && !h.name().startsWith("payments.")) completedOthers.incrementAndGet();
        }
    }

    private TelemetryHandlerRegistry registry;
    private TelemetryFlowSinkScanner scanner;

    @BeforeEach
    void setUp() {
        registry = new TelemetryHandlerRegistry();
        scanner = new TelemetryFlowSinkScanner(registry);
        scanner.postProcessAfterInitialization(new OrdersOrPayments(), "rcv");
        OrdersOrPayments.successOrders.set(0);
        OrdersOrPayments.successPayments.set(0);
        OrdersOrPayments.completedOthers.set(0);
    }

    @Test
    void multiple_prefixes_are_ORed_and_success_only_on_completed() throws Exception {
        FlowEvent o = FlowEvent.builder()
                .name("orders.create")
                .timestamp(Instant.now())
                .build();
        o.eventContext().put("lifecycle", "COMPLETED");

        FlowEvent p = FlowEvent.builder()
                .name("payments.charge")
                .timestamp(Instant.now())
                .build();
        p.eventContext().put("lifecycle", "COMPLETED");

        FlowEvent other = FlowEvent.builder()
                .name("inventory.update")
                .timestamp(Instant.now())
                .build();
        other.eventContext().put("lifecycle", "COMPLETED");

        for (var h : registry.handlers()) {
            h.handle(o);
            h.handle(p);
            h.handle(other);
        }

        assertThat(OrdersOrPayments.successOrders.get()).isEqualTo(1);
        assertThat(OrdersOrPayments.successPayments.get()).isEqualTo(1);
        // Out-of-scope completed handler should not fire
        assertThat(OrdersOrPayments.completedOthers.get()).isEqualTo(0);
    }
}

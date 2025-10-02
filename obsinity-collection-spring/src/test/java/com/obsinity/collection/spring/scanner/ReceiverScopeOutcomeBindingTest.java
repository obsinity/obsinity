package com.obsinity.collection.spring.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.FlowException;
import com.obsinity.collection.api.annotations.OnAllLifecycles;
import com.obsinity.collection.api.annotations.OnEventScope;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.collection.api.annotations.OnOutcome;
import com.obsinity.collection.api.annotations.Outcome;
import com.obsinity.collection.api.annotations.PullAllAttributes;
import com.obsinity.collection.api.annotations.PullAllContextValues;
import com.obsinity.collection.api.annotations.PullAttribute;
import com.obsinity.collection.api.annotations.PullContextValue;
import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.telemetry.model.TelemetryEvent;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiverScopeOutcomeBindingTest {

    @EventReceiver
    @OnEventScope("checkout")
    @OnAllLifecycles
    static class CheckoutReceiver {
        static volatile String lastUserId;
        static volatile Integer lastCart;
        static volatile Map<String, Object> lastAttrs;
        static volatile Map<String, Object> lastCtx;
        static final AtomicInteger started = new AtomicInteger();
        static final AtomicInteger success = new AtomicInteger();
        static final AtomicInteger failure = new AtomicInteger();
        static final AtomicInteger fallback = new AtomicInteger();
        static volatile Throwable lastThrowable;

        @OnFlowStarted
        public void onStart(
                @PullAttribute("user.id") String userId,
                @PullAllAttributes Map<String, Object> attrs,
                @PullContextValue("cart.size") Integer cart,
                @PullAllContextValues Map<String, Object> ctx) {
            started.incrementAndGet();
            lastUserId = userId;
            lastCart = cart;
            lastAttrs = attrs;
            lastCtx = ctx;
        }

        @OnFlowCompleted
        @OnOutcome(Outcome.SUCCESS)
        public void onSuccess(@PullAllAttributes Map<String, Object> attrs) {
            success.incrementAndGet();
            lastAttrs = attrs;
        }

        @OnFlowCompleted
        @OnOutcome(Outcome.FAILURE)
        public void onFailure(@FlowException(FlowException.Source.ROOT) Throwable root) {
            failure.incrementAndGet();
            lastThrowable = root;
        }

        @com.obsinity.collection.api.annotations.OnFlowNotMatched
        public void onNotMatched() {
            fallback.incrementAndGet();
        }
    }

    @EventReceiver
    static class DotChopReceiver {
        static final AtomicInteger called = new AtomicInteger();

        @OnFlowStarted
        @OnEventScope("a.b")
        public void onScopedStart() {
            called.incrementAndGet();
        }
    }

    private TelemetryHandlerRegistry registry;
    private TelemetryEventReceiverScanner scanner;

    @BeforeEach
    void setUp() {
        registry = new TelemetryHandlerRegistry();
        scanner = new TelemetryEventReceiverScanner(registry);

        scanner.postProcessAfterInitialization(new CheckoutReceiver(), "checkoutReceiver");
        scanner.postProcessAfterInitialization(new DotChopReceiver(), "dotChopReceiver");

        // reset counters/state
        CheckoutReceiver.started.set(0);
        CheckoutReceiver.success.set(0);
        CheckoutReceiver.failure.set(0);
        CheckoutReceiver.fallback.set(0);
        CheckoutReceiver.lastUserId = null;
        CheckoutReceiver.lastCart = null;
        CheckoutReceiver.lastAttrs = null;
        CheckoutReceiver.lastCtx = null;
        CheckoutReceiver.lastThrowable = null;
        DotChopReceiver.called.set(0);
    }

    @Test
    void binds_pull_parameters_on_started_and_filters_by_class_scope() throws Exception {
        TelemetryEvent h = TelemetryEvent.builder()
                .name("checkout.payment")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "STARTED");
        h.attributes().put("user.id", "alice");
        h.attributes().put("amount", 42);
        h.eventContext().put("cart.size", 3);

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(CheckoutReceiver.started.get()).isEqualTo(1);
        assertThat(CheckoutReceiver.lastUserId).isEqualTo("alice");
        assertThat(CheckoutReceiver.lastCart).isEqualTo(3);
        assertThat(CheckoutReceiver.lastAttrs).containsEntry("user.id", "alice").containsEntry("amount", 42);
        assertThat(CheckoutReceiver.lastCtx).containsEntry("cart.size", 3);
    }

    @Test
    void invokes_success_handler_only_for_completed_and_within_scope() throws Exception {
        TelemetryEvent h = TelemetryEvent.builder()
                .name("checkout.reserve")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "COMPLETED");
        h.attributes().put("user.id", "bob");

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(CheckoutReceiver.success.get()).isEqualTo(1);
        assertThat(CheckoutReceiver.failure.get()).isEqualTo(0);
    }

    @Test
    void invokes_failure_handler_with_root_throwable_when_failed() throws Exception {
        TelemetryEvent h = TelemetryEvent.builder()
                .name("checkout.reserve")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "FAILED");
        Throwable root = new IllegalArgumentException("bad");
        Throwable wrapped = new RuntimeException("wrap", root);
        h.setThrowable(wrapped);

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(CheckoutReceiver.failure.get()).isEqualTo(1);
        assertThat(CheckoutReceiver.lastThrowable).isSameAs(root);
    }

    @Test
    void component_fallback_invoked_when_no_handler_matches_in_receiver() throws Exception {
        TelemetryEvent h = TelemetryEvent.builder()
                .name("billing.charge")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "COMPLETED");

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(CheckoutReceiver.fallback.get()).isEqualTo(1);
    }

    @Test
    void dot_chop_scope_matches_prefix() throws Exception {
        TelemetryEvent h =
                TelemetryEvent.builder().name("a.b.c").timestamp(Instant.now()).build();
        h.eventContext().put("lifecycle", "STARTED");

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(DotChopReceiver.called.get()).isEqualTo(1);
    }
}

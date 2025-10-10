package com.obsinity.collection.spring.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.api.annotations.FlowException;
import com.obsinity.collection.api.annotations.FlowSink;
import com.obsinity.collection.api.annotations.OnAllLifecycles;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowScope;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.collection.api.annotations.OnOutcome;
import com.obsinity.collection.api.annotations.Outcome;
import com.obsinity.collection.api.annotations.PullAllAttributes;
import com.obsinity.collection.api.annotations.PullAllContextValues;
import com.obsinity.collection.api.annotations.PullAttribute;
import com.obsinity.collection.api.annotations.PullContextValue;
import com.obsinity.collection.core.sinks.FlowHandlerRegistry;
import com.obsinity.flow.model.FlowEvent;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SinkScopeOutcomeBindingTest {

    @FlowSink
    @OnFlowScope("checkout")
    @OnAllLifecycles
    static class CheckoutSink {
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

    @FlowSink
    static class DotChopSink {
        static final AtomicInteger called = new AtomicInteger();

        @OnFlowStarted
        @OnFlowScope("a.b")
        public void onScopedStart() {
            called.incrementAndGet();
        }
    }

    private FlowHandlerRegistry registry;
    private FlowSinkScanner scanner;

    @BeforeEach
    void setUp() {
        registry = new FlowHandlerRegistry();
        scanner = new FlowSinkScanner(registry);

        scanner.postProcessAfterInitialization(new CheckoutSink(), "checkoutSink");
        scanner.postProcessAfterInitialization(new DotChopSink(), "dotChopSink");

        // reset counters/state
        CheckoutSink.started.set(0);
        CheckoutSink.success.set(0);
        CheckoutSink.failure.set(0);
        CheckoutSink.fallback.set(0);
        CheckoutSink.lastUserId = null;
        CheckoutSink.lastCart = null;
        CheckoutSink.lastAttrs = null;
        CheckoutSink.lastCtx = null;
        CheckoutSink.lastThrowable = null;
        DotChopSink.called.set(0);
    }

    @Test
    void binds_pull_parameters_on_started_and_filters_by_class_scope() throws Exception {
        FlowEvent h = FlowEvent.builder()
                .name("checkout.payment")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "STARTED");
        h.attributes().put("user.id", "alice");
        h.attributes().put("amount", 42);
        h.eventContext().put("cart.size", 3);

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(CheckoutSink.started.get()).isEqualTo(1);
        assertThat(CheckoutSink.lastUserId).isEqualTo("alice");
        assertThat(CheckoutSink.lastCart).isEqualTo(3);
        assertThat(CheckoutSink.lastAttrs).containsEntry("user.id", "alice").containsEntry("amount", 42);
        assertThat(CheckoutSink.lastCtx).containsEntry("cart.size", 3);
    }

    @Test
    void invokes_success_handler_only_for_completed_and_within_scope() throws Exception {
        FlowEvent h = FlowEvent.builder()
                .name("checkout.reserve")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "COMPLETED");
        h.attributes().put("user.id", "bob");

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(CheckoutSink.success.get()).isEqualTo(1);
        assertThat(CheckoutSink.failure.get()).isEqualTo(0);
    }

    @Test
    void invokes_failure_handler_with_root_throwable_when_failed() throws Exception {
        FlowEvent h = FlowEvent.builder()
                .name("checkout.reserve")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "FAILED");
        Throwable root = new IllegalArgumentException("bad");
        Throwable wrapped = new RuntimeException("wrap", root);
        h.setThrowable(wrapped);

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(CheckoutSink.failure.get()).isEqualTo(1);
        assertThat(CheckoutSink.lastThrowable).isSameAs(root);
    }

    @Test
    void component_fallback_invoked_when_no_handler_matches_in_sink() throws Exception {
        FlowEvent h = FlowEvent.builder()
                .name("billing.charge")
                .timestamp(Instant.now())
                .build();
        h.eventContext().put("lifecycle", "COMPLETED");

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(CheckoutSink.fallback.get()).isEqualTo(1);
    }

    @Test
    void dot_chop_scope_matches_prefix() throws Exception {
        FlowEvent h = FlowEvent.builder().name("a.b.c").timestamp(Instant.now()).build();
        h.eventContext().put("lifecycle", "STARTED");

        for (var handler : registry.handlers()) handler.handle(h);

        assertThat(DotChopSink.called.get()).isEqualTo(1);
    }
}

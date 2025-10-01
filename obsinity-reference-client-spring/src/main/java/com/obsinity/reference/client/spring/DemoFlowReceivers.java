package com.obsinity.reference.client.spring;

import com.obsinity.collection.api.annotations.*;
import com.obsinity.telemetry.model.TelemetryHolder;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sample receivers showcasing common combinations and parameter bindings.
 */
@EventReceiver
@OnEventScope("demo.") // handle demo.* flows by default
public class DemoFlowReceivers {
    private static final Logger log = LoggerFactory.getLogger(DemoFlowReceivers.class);

    /**
     * Handle start of all demo.* flows.
     */
    @OnFlowStarted
    public void onStart(TelemetryHolder h, @PullAllAttributes Map<String, Object> attrs) {
        log.info("START {} attrs={} ctx={}", h.name(), attrs, h.eventContext());
    }

    /**
     * Success finishes only.
     */
    @OnFlowCompleted
    @OnOutcome(Outcome.SUCCESS)
    public void onSuccess(@PullAllAttributes Map<String, Object> attrs) {
        log.info("DONE  attrs={}", attrs);
    }

    /**
     * Failure finishes only with Throwable binding.
     */
    @OnFlowCompleted
    @OnOutcome(Outcome.FAILURE)
    public void onFailure(Throwable t, @PullAllAttributes Map<String, Object> attrs) {
        log.warn("FAIL  ex={} attrs={}", t.toString(), attrs);
    }

    /**
     * Failure with type-specific binding wins when multiple match.
     */
    @OnFlowFailure
    public void onIllegalArg(IllegalArgumentException ex, TelemetryHolder h) {
        log.warn("FAIL-IAE {} ex={}", h.name(), ex.getMessage());
    }

    /**
     * Guarded handler requiring attributes to be present.
     */
    @OnFlowStarted
    @RequiredAttributes({"user.id", "cart.size"})
    public void guarded(@PullAttribute("user.id") String uid, @PullContextValue("cart.size") Integer items) {
        log.info("GUARD user={} items={}", uid, items);
    }

    /**
     * Component fallback when no other handler in this receiver matched.
     */
    @OnFlowNotMatched
    public void notMatched(TelemetryHolder h) {
        log.debug("FALLBACK {} attrs={} ctx={}", h.name(), h.attributes().map(), h.eventContext());
    }
}

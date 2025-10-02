package com.obsinity.reference.client.spring;

import com.obsinity.collection.api.annotations.*;
import com.obsinity.telemetry.model.TelemetryEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receivers for demo.* flows that illustrate selection rules, outcome filters, and parameter binding.
 *
 * <p>Scope and matching
 * - Class scope {@code @OnEventScope("demo.")} selects events whose names start with {@code demo.}.
 *   A trailing dot means "treat as prefix". Dot-chop fallback also applies for exact scopes (a.b.c → a.b → a → "").
 * - Method annotations refine selection further: lifecycle ({@code @OnFlowStarted}, {@code @OnFlowCompleted},
 *   {@code @OnFlowFailure}) and outcomes ({@code @OnOutcome}).
 *
 * <p>Handlers in this class demonstrate:
 * - Start handler with {@code @PullAllAttributes}
 * - Finish handlers split by outcome with Throwable injection on failure
 * - Failure handler with most-specific Throwable type matching (IllegalArgumentException)
 * - Guarded handler requiring attributes/context via {@code @RequiredAttributes} and {@code @Pull*}
 * - Per-receiver fallback via {@code @OnFlowNotMatched} when nothing else in this bean matches
 */
@EventReceiver
@OnEventScope("demo.") // handle demo.* flows by default
public class DemoFlowReceivers {
    private static final Logger log = LoggerFactory.getLogger(DemoFlowReceivers.class);

    /**
     * Fires when:
     * - Lifecycle = STARTED
     * - Event name matches class scope {@code demo.*} (prefix match with trailing dot semantics)
     * - No additional attribute/context requirements
     * Binds: full attributes map via {@code @PullAllAttributes}.
     */
    @OnFlowStarted
    public void onStart(TelemetryEvent event, @PullAllAttributes Map<String, Object> attrs) {
        log.info("START {} attrs={} ctx={}", event.name(), attrs, event.eventContext());
    }

    /**
     * Fires when:
     * - Lifecycle = COMPLETED (success path)
     * - Event name matches class scope {@code demo.*}
     * Ignores: failures (they are handled by {@link #onFailure(Throwable, Map)}).
     * Binds: full attributes map.
     */
    @OnFlowCompleted
    @OnOutcome(Outcome.SUCCESS)
    public void onSuccess(@PullAllAttributes Map<String, Object> attrs) {
        log.info("DONE  attrs={}", attrs);
    }

    /**
     * Fires when:
     * - Lifecycle = FAILED
     * - Event name matches class scope {@code demo.*}
     * - Only if no {@code @OnFlowFailure}-annotated handler in this receiver matched
     * Notes: This represents the finish (failure) event and receives the Throwable; suppressed when a
     *        more specific {@code @OnFlowFailure} handler in this bean has handled the failure.
     * Binds: failure Throwable and full attributes map.
     */
    @OnFlowCompleted
    @OnOutcome(Outcome.FAILURE)
    public void onFailure(Throwable t, @PullAllAttributes Map<String, Object> attrs) {
        log.warn("FAIL  ex={} attrs={}", t.toString(), attrs);
    }

    /**
     * Fires when:
     * - Lifecycle = FAILED
     * - Throwable is an IllegalArgumentException (or subtype)
     * - Event name matches class scope {@code demo.*}
     * Binds: the specific IllegalArgumentException and the full TelemetryEvent.
     */
    @OnFlowFailure
    public void onIllegalArg(IllegalArgumentException ex, TelemetryEvent event) {
        log.warn("FAIL-IAE {} ex={}", event.name(), ex.getMessage());
    }

    /**
     * Fires when:
     * - Lifecycle = STARTED
     * - Event name matches class scope {@code demo.*}
     * - Required attributes present: {@code user.id} and {@code cart.size}
     * Binds: {@code user.id} attribute and {@code cart.size} context value as parameters.
     */
    @OnFlowStarted
    @RequiredAttributes({"user.id", "cart.size"})
    public void guarded(@PullAttribute("user.id") String uid, @PullContextValue("cart.size") Integer items) {
        log.info("GUARD user={} items={}", uid, items);
    }

    /**
     * Fires when:
     * - No other handler method in this receiver matched the incoming event
     * - Runs regardless of lifecycle/name (used as a last resort within this bean only)
     */
    @OnFlowNotMatched
    public void notMatched(TelemetryEvent event) {
        log.debug("FALLBACK {} attrs={} ctx={}", event.name(), event.attributes().map(), event.eventContext());
    }
}

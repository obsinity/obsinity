package com.obsinity.flow.processor;

import com.obsinity.collection.api.annotations.OrphanAlert;
import com.obsinity.flow.model.FlowEvent;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-local flow context + batching helpers. (Dispatching is handled by AsyncDispatchBus in FlowProcessor.)
 */
public class FlowProcessorSupport {

    private static final Logger log = LoggerFactory.getLogger(FlowProcessorSupport.class);
    public static final String STEP_EXECUTED_WITH_NO_ACTIVE_FLOW_AUTO_PROMOTED_TO_FLOW =
            "Step '{}' executed with no active Flow; auto-promoted to Flow.";

    /** Flag to control whether telemetry is enabled. When false, all operations become no-ops. */
    private volatile boolean enabled = true;

    /** Per-thread stack of active flows/holders (top = current). */
    private final InheritableThreadLocal<Deque<FlowEvent>> ctx;

    /**
     * Per-thread, per-root in-order list of completed {@link FlowEvent}s. Created when the root opens; appended
     * to on flow start (for batching), emitted and cleared at root exit.
     */
    private final InheritableThreadLocal<List<FlowEvent>> batch;

    public FlowProcessorSupport() {
        this.ctx = new InheritableThreadLocal<>() {
            @Override
            protected Deque<FlowEvent> initialValue() {
                return new ArrayDeque<>();
            }
        };
        this.batch = new InheritableThreadLocal<>() {
            @Override
            protected List<FlowEvent> initialValue() {
                return new ArrayList<>();
            }
        };
    }

    /* --------------------- enabled flag --------------------- */

    /**
     * Returns whether telemetry is enabled.
     *
     * @return {@code true} if telemetry is enabled (default), {@code false} if disabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether telemetry is enabled.
     * When disabled, most operations become no-ops to minimize overhead.
     *
     * @param enabled {@code true} to enable telemetry, {@code false} to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /* --------------------- flow stack --------------------- */

    public FlowEvent currentContext() {
        if (!enabled) return null;
        final Deque<FlowEvent> d = ctx.get();
        return d.isEmpty() ? null : d.peekLast();
    }

    /** Returns the context just below the current top (the parent), or null if none. */
    FlowEvent currentContextBelowTop() {
        final Deque<FlowEvent> d = ctx.get();
        if (d.size() < 2) return null;
        final var it = d.descendingIterator();
        it.next(); // skip top
        return it.next(); // parent
    }

    public boolean hasActiveFlow() {
        return !ctx.get().isEmpty();
    }

    public void push(final FlowEvent event) {
        if (event != null) ctx.get().addLast(event);
    }

    public void pop(final FlowEvent expectedTop) {
        final Deque<FlowEvent> d = ctx.get();
        if (!d.isEmpty()) {
            final FlowEvent last = d.peekLast();
            if (last == expectedTop) {
                d.removeLast();
                if (d.isEmpty()) {
                    ctx.remove(); // Clean up ThreadLocal when stack is empty
                }
            } else {
                // inconsistent nesting; log warning and clean up completely
                log.warn(
                        "Inconsistent flow nesting detected: expected {} but found {}. Cleaning up thread state.",
                        expectedTop != null ? expectedTop.name() : "null",
                        last != null ? last.name() : "null");
                ctx.remove(); // Remove ThreadLocal completely
            }
        }
    }

    /* --------------------- batch helpers --------------------- */

    /** Start a fresh batch for a new root flow. */
    public void startNewBatch() {
        batch.set(new ArrayList<>());
    }

    /** Add a holder to the current root batch if present. */
    public void addToBatch(final FlowEvent event) {
        final List<FlowEvent> list = batch.get();
        if (list != null && event != null) list.add(event);
    }

    /**
     * Return the current batch as-is (may be empty). <b>Does not clear.</b> Clearing must be done by
     * {@link #clearBatchAfterDispatch()} after dispatch completes.
     */
    public List<FlowEvent> finishBatchAndGet() {
        return batch.get();
    }

    /** Convenience accessor if a binder prefers non-null/immutable empty. */
    public List<FlowEvent> getBatch() {
        return batch.get();
    }

    /** Clear the batch <b>after</b> ROOT_FLOW_FINISHED dispatch so binders can read it during invocation. */
    public void clearBatchAfterDispatch() {
        batch.remove();
    }

    /* --------------------- mutation helpers --------------------- */

    public void setEndTime(final FlowEvent event, final Instant end) {
        if (event != null) event.setEndTimestamp(end);
    }

    /* --------------------- utility --------------------- */

    public long unixNanos(final Instant ts) {
        return ts.getEpochSecond() * 1_000_000_000L + ts.getNano();
    }

    interface UnsafeRunnable {
        void run() throws Exception;
    }

    public void safe(final UnsafeRunnable r) {
        try {
            r.run();
        } catch (Exception ignored) {
            // Intentionally ignore to keep the main flow healthy
        }
    }

    /* --------------------- ThreadLocal cleanup --------------------- */

    /**
     * Clean up all ThreadLocal state. Should be called at request/transaction boundaries to prevent memory leaks
     * in thread pool environments.
     */
    public void cleanupThreadLocals() {
        try {
            final Deque<FlowEvent> d = ctx.get();
            if (d != null && !d.isEmpty()) {
                // Log warning about unpopped events - indicates potential bug
                log.warn(
                        "Cleaning up {} unpopped FlowEvent(s) from thread {}. This may indicate improper flow nesting or missing exception handling.",
                        d.size(),
                        Thread.currentThread().getName());
            }
        } catch (Exception e) {
            log.debug("Error checking ctx ThreadLocal during cleanup", e);
        } finally {
            ctx.remove();
        }

        try {
            final List<FlowEvent> b = batch.get();
            if (b != null && !b.isEmpty()) {
                log.debug(
                        "Cleaning up {} undispatched FlowEvent(s) from batch on thread {}",
                        b.size(),
                        Thread.currentThread().getName());
            }
        } catch (Exception e) {
            log.debug("Error checking batch ThreadLocal during cleanup", e);
        } finally {
            batch.remove();
        }
    }

    /* --------------------- orphan step logging --------------------- */

    /**
     * Log that a @Step executed with no active Flow and is being promoted. Default level is ERROR if {@code level} is
     * null.
     */
    public void logOrphanStep(final String stepName, final OrphanAlert.Level level) {
        final OrphanAlert.Level lvl = (level != null ? level : OrphanAlert.Level.ERROR);
        switch (lvl) {
            case NONE -> {
                /* no-op */
            }
            case TRACE -> log.trace(STEP_EXECUTED_WITH_NO_ACTIVE_FLOW_AUTO_PROMOTED_TO_FLOW, stepName);
            case ERROR -> log.error(STEP_EXECUTED_WITH_NO_ACTIVE_FLOW_AUTO_PROMOTED_TO_FLOW, stepName);
            case WARN -> log.warn(STEP_EXECUTED_WITH_NO_ACTIVE_FLOW_AUTO_PROMOTED_TO_FLOW, stepName);
            case INFO -> log.info(STEP_EXECUTED_WITH_NO_ACTIVE_FLOW_AUTO_PROMOTED_TO_FLOW, stepName);
            case DEBUG -> log.debug(STEP_EXECUTED_WITH_NO_ACTIVE_FLOW_AUTO_PROMOTED_TO_FLOW, stepName);
            default -> log.error(STEP_EXECUTED_WITH_NO_ACTIVE_FLOW_AUTO_PROMOTED_TO_FLOW, stepName);
        }
    }
}

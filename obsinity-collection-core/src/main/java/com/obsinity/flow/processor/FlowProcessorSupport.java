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

    /* --------------------- flow stack --------------------- */

    public FlowEvent currentHolder() {
        final Deque<FlowEvent> d = ctx.get();
        return d.isEmpty() ? null : d.peekLast();
    }

    /** Returns the holder just below the current top (the parent), or null if none. */
    FlowEvent currentHolderBelowTop() {
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
            } else {
                d.clear(); // inconsistent nesting; reset to avoid leaks
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
        // re-initialize to avoid extra allocations on the next root
        batch.set(new ArrayList<>());
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

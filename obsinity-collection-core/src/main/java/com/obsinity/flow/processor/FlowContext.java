package com.obsinity.flow.processor;

import com.obsinity.flow.model.FlowEvent;
import java.util.Map;
import java.util.Objects;

/**
 * Facade for writing to the current FlowEvent (flow or step).
 *
 * <ul>
 *   <li><b>Attributes</b> (persisted): {@link #putAttr(String, Object)} / {@link #putAllAttrs(Map)} — written to
 *       {@link FlowEvent#attributes()}.
 *   <li><b>EventContext</b> (ephemeral): {@link #putContext(String, Object)} / {@link #putAllContext(Map)} — written to
 *       {@link FlowEvent#getEventContext()}.
 * </ul>
 */
public class FlowContext {

    private final FlowProcessorSupport support;

    public FlowContext(FlowProcessorSupport support) {
        this.support = Objects.requireNonNull(support, "FlowProcessorSupport must not be null");
    }

    /* ===================== Attributes (persisted) ===================== */

    /** Back-compat alias for {@link #putAttr(String, Object)} that returns the same typed value. */
    public <T> T put(String key, T value) {
        return putAttr(key, value);
    }

    /** Back-compat alias for {@link #putAllAttrs(Map)}. */
    public void putAll(Map<String, ?> map) {
        putAllAttrs(map);
    }

    /** Adds a single attribute to the current context and returns the same typed value. */
    public <T> T putAttr(String key, T value) {
        // No-op if telemetry is disabled - check first for best performance
        if (!support.isEnabled()) return value;
        if (key == null || key.isBlank()) return value;
        FlowEvent context = support.currentContext();
        if (context != null) {
            context.attributes().put(key, value);
        }
        return value;
    }

    /** Adds all entries as attributes to the current context. */
    public void putAllAttrs(Map<String, ?> map) {
        // No-op if telemetry is disabled - check first for best performance
        if (!support.isEnabled()) return;
        if (map == null || map.isEmpty()) return;
        FlowEvent context = support.currentContext();
        if (context == null) return;

        map.forEach((k, v) -> {
            if (k != null && !k.isBlank()) {
                context.attributes().put(k, v);
            }
        });
    }

    /* ===================== EventContext (ephemeral) ===================== */

    /** Adds a single EventContext entry to the current context and returns the same typed value. */
    public <T> T putContext(String key, T value) {
        // No-op if telemetry is disabled - check first for best performance
        if (!support.isEnabled()) return value;
        if (key == null || key.isBlank()) return value;
        FlowEvent context = support.currentContext();
        if (context != null) {
            context.getEventContext().put(key, value);
        }
        return value;
    }

    /** Adds all entries to the EventContext of the current context. */
    public void putAllContext(Map<String, ?> map) {
        // No-op if telemetry is disabled - check first for best performance
        if (!support.isEnabled()) return;
        if (map == null || map.isEmpty()) return;
        FlowEvent context = support.currentContext();
        if (context == null) return;

        map.forEach((k, v) -> {
            if (k != null && !k.isBlank()) {
                context.getEventContext().put(k, v);
            }
        });
    }
}

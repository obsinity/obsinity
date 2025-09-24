package com.obsinity.telemetry.processor;

import com.obsinity.telemetry.model.TelemetryHolder;
import java.util.Map;
import java.util.Objects;

/**
 * Facade for writing to the current TelemetryHolder (flow or step).
 *
 * <ul>
 *   <li><b>Attributes</b> (persisted): {@link #putAttr(String, Object)} / {@link #putAllAttrs(Map)} — written to
 *       {@link TelemetryHolder#attributes()}.
 *   <li><b>EventContext</b> (ephemeral): {@link #putContext(String, Object)} / {@link #putAllContext(Map)} — written to
 *       {@link TelemetryHolder#getEventContext()}.
 * </ul>
 */
public class TelemetryContext {

    private final TelemetryProcessorSupport support;

    public TelemetryContext(TelemetryProcessorSupport support) {
        this.support = Objects.requireNonNull(support, "TelemetryProcessorSupport must not be null");
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

    /** Adds a single attribute to the current holder and returns the same typed value. */
    public <T> T putAttr(String key, T value) {
        if (key == null || key.isBlank()) return value;
        TelemetryHolder holder = support.currentHolder();
        if (holder != null) {
            holder.attributes().put(key, value);
        }
        return value;
    }

    /** Adds all entries as attributes to the current holder. */
    public void putAllAttrs(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return;
        TelemetryHolder holder = support.currentHolder();
        if (holder == null) return;

        map.forEach((k, v) -> {
            if (k != null && !k.isBlank()) {
                holder.attributes().put(k, v);
            }
        });
    }

    /* ===================== EventContext (ephemeral) ===================== */

    /** Adds a single EventContext entry to the current holder and returns the same typed value. */
    public <T> T putContext(String key, T value) {
        if (key == null || key.isBlank()) return value;
        TelemetryHolder holder = support.currentHolder();
        if (holder != null) {
            holder.getEventContext().put(key, value);
        }
        return value;
    }

    /** Adds all entries to the EventContext of the current holder. */
    public void putAllContext(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return;
        TelemetryHolder holder = support.currentHolder();
        if (holder == null) return;

        map.forEach((k, v) -> {
            if (k != null && !k.isBlank()) {
                holder.getEventContext().put(k, v);
            }
        });
    }
}

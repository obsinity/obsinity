package com.obsinity.telemetry.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.opentelemetry.api.common.Attributes; // API (safe)
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@JsonInclude(Include.NON_NULL)
@Getter
@Setter
public final class OEvent {
    private String name;
    /** Wall-clock start time in epoch nanos. */
    private long epochNanos;
    /** Optional wall-clock end time in epoch nanos. */
    private Long endEpochNanos;

    private String kind;
    private OAttributes attributes;
    /** Count of attributes dropped when creating this event (for telemetry parity). */
    private Integer droppedAttributesCount;

    private OStatus status;
    private List<OEvent> events;

    /** Monotonic start (for duration math); not serialized. */
    @JsonIgnore
    private long startNanoTime;

    /** Step-scoped context; not serialized. */
    @JsonIgnore
    private Map<String, Object> eventContext;

    public OEvent() {}

    public OEvent(
            String name,
            long epochNanos,
            Long endEpochNanos,
            OAttributes attributes,
            Integer droppedAttributesCount,
            long startNanoTime) {
        this(
                name,
                epochNanos,
                endEpochNanos,
                attributes,
                droppedAttributesCount,
                startNanoTime,
                new LinkedHashMap<>(),
                null,
                new java.util.ArrayList<>());
    }

    public OEvent(
            String name,
            long epochNanos,
            Long endEpochNanos,
            OAttributes attributes,
            Integer droppedAttributesCount,
            long startNanoTime,
            Map<String, Object> eventContext,
            OStatus status,
            java.util.List<OEvent> events) {
        this.name = Objects.requireNonNull(name, "name");
        this.epochNanos = epochNanos;
        this.endEpochNanos = endEpochNanos;
        this.attributes = (attributes == null ? new OAttributes(new LinkedHashMap<>()) : attributes);
        this.droppedAttributesCount = droppedAttributesCount;
        this.startNanoTime = startNanoTime;
        this.eventContext = (eventContext != null ? eventContext : new LinkedHashMap<>());
        this.status = status;
        this.events = (events != null ? events : new java.util.ArrayList<>());
    }

    public String name() {
        return name;
    }

    public long epochNanos() {
        return epochNanos;
    }

    public Long endEpochNanos() {
        return endEpochNanos;
    }

    public OAttributes attributes() {
        return attributes;
    }

    public Integer droppedAttributesCount() {
        return droppedAttributesCount;
    }

    /** Step-scoped EventContext (non-serialized, mutable). */
    @JsonIgnore
    public Map<String, Object> eventContext() {
        return eventContext;
    }

    // -------- EventContext helpers --------
    public void eventContextPut(final String key, final Object value) {
        if (key == null || key.isBlank()) return;
        eventContext.put(key, value);
    }

    public Object eventContextGet(final String key) {
        return (key == null) ? null : eventContext.get(key);
    }

    public boolean hasEventContextKey(final String key) {
        return key != null && eventContext.containsKey(key);
    }

    @JsonIgnore
    public Map<String, Object> eventContextView() {
        return Collections.unmodifiableMap(eventContext);
    }

    // -------- API helper (safe to use everywhere) --------
    /** Convert just the attributes to OTEL API Attributes (no SDK types). */
    public Attributes toOtelAttributes() {
        return attributes.toOtel(); // OAttributes should already be API-only
    }

    /** Total attribute count (actual + dropped), for parity with OTEL semantics. */
    public int totalAttributeCount() {
        final Attributes a = attributes.toOtel();
        final int dropped = (droppedAttributesCount == null ? 0 : droppedAttributesCount);
        return a.size() + dropped;
    }

    @JsonIgnore
    public List<OEvent> ensureEvents() {
        if (events == null) events = new java.util.ArrayList<>();
        return events;
    }
}

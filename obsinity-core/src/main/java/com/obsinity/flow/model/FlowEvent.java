package com.obsinity.flow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OTEL-shaped telemetry container with Obsinity-native fields.
 *
 * <p><strong>Service ID requirement:</strong> You MUST provide a service identifier either at the top level
 * {@link #serviceId} or in {@code resource.attributes["service.id"]}. If both exist they must match. Use
 * {@link #effectiveServiceId()} to read the resolved value.
 */
@JsonInclude(Include.NON_NULL)
public class FlowEvent {

    public static final String SERVICE_ID_ATTR = "service.id";

    /* ========================= Embedded Builder ========================= */

    /** Create a new builder for {@link FlowEvent}. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private Instant timestamp;
        private Long timeUnixNano;
        private Instant endTimestamp;
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private SpanKind kind;
        private OResource resource;
        private OAttributes attributes = new OAttributes(new LinkedHashMap<>());
        private List<OEvent> events = new ArrayList<>();
        private List<OLink> links = new ArrayList<>();
        private OStatus status;
        private String serviceId;
        private String correlationId;
        private Boolean synthetic;
        private Long elapsedNanos;
        private Object returnValue;
        private boolean returnValueCaptured;
        private Map<String, Object> eventContext = new LinkedHashMap<>(); // flow-scoped (non-serialized)

        // step emulation metadata (non-serialized)
        private boolean step;
        private long startNanoTime;

        private Builder() {
            this.serviceId = "**DUMMY-SERVICE-ID**";
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder timeUnixNano(Long timeUnixNano) {
            this.timeUnixNano = timeUnixNano;
            return this;
        }

        public Builder endTimestamp(Instant endTimestamp) {
            this.endTimestamp = endTimestamp;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder spanId(String spanId) {
            this.spanId = spanId;
            return this;
        }

        public Builder parentSpanId(String parentSpanId) {
            this.parentSpanId = parentSpanId;
            return this;
        }

        public Builder kind(SpanKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder resource(OResource resource) {
            this.resource = resource;
            return this;
        }

        public Builder attributes(OAttributes attributes) {
            this.attributes = attributes;
            return this;
        }

        public Builder putAttribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder events(List<OEvent> events) {
            this.events = events;
            return this;
        }

        public Builder addEvent(OEvent event) {
            this.events.add(event);
            return this;
        }

        public Builder links(List<OLink> links) {
            this.links = links;
            return this;
        }

        public Builder addLink(OLink link) {
            this.links.add(link);
            return this;
        }

        public Builder status(OStatus status) {
            this.status = status;
            return this;
        }

        public Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder synthetic(Boolean synthetic) {
            this.synthetic = synthetic;
            return this;
        }

        public Builder returnValue(Object returnValue) {
            this.returnValue = returnValue;
            this.returnValueCaptured = true;
            return this;
        }

        public Builder clearReturnValue() {
            this.returnValue = null;
            this.returnValueCaptured = false;
            return this;
        }

        public Builder elapsedNanos(Long elapsedNanos) {
            this.elapsedNanos = elapsedNanos;
            return this;
        }

        public Builder eventContext(Map<String, Object> eventContext) {
            this.eventContext = (eventContext != null ? eventContext : new LinkedHashMap<>());
            return this;
        }

        public Builder putEventContext(String key, Object value) {
            this.eventContext.put(key, value);
            return this;
        }

        /** Mark this event as representing a promoted step (not serialized). */
        public Builder step(boolean step) {
            this.step = step;
            return this;
        }
        /** Set monotonic start (nanoTime) for accurate duration when folding steps (not serialized). */
        public Builder startNanoTime(long t) {
            this.startNanoTime = t;
            return this;
        }

        public FlowEvent build() {
            FlowEvent context = new FlowEvent(
                    name,
                    timestamp,
                    timeUnixNano,
                    endTimestamp,
                    traceId,
                    spanId,
                    parentSpanId,
                    kind,
                    resource,
                    attributes != null ? attributes : new OAttributes(new LinkedHashMap<>()),
                    events != null ? events : new ArrayList<>(),
                    links != null ? links : new ArrayList<>(),
                    status,
                    serviceId,
                    correlationId,
                    synthetic,
                    elapsedNanos,
                    returnValue,
                    returnValueCaptured);
            if (eventContext != null && !eventContext.isEmpty()) {
                context.eventContext().putAll(eventContext);
            }
            context.step = this.step;
            context.startNanoTime = this.startNanoTime;
            return context;
        }
    }

    /* ── OTEL-ish core ───────────────────────────────────────────── */
    private String name;
    private Instant timestamp;
    private Long timeUnixNano;
    private Instant endTimestamp;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private SpanKind kind; // OTEL enum
    private OResource resource; // wrapper
    private OAttributes attributes; // wrapper
    private List<OEvent> events; // mutable
    private List<OLink> links; // mutable
    private OStatus status; // wrapper

    /* ── Obsinity-native ─────────────────────────────────────────── */
    private String serviceId; // required here OR in resource["service.id"]
    private String correlationId;
    private Boolean synthetic;
    private Long elapsedNanos;

    @JsonProperty("return")
    private Object returnValue;

    @JsonIgnore
    private boolean returnValueCaptured;

    /* ── EventContext (flow-scoped, non-serialized) ──────────────── */
    @JsonIgnore
    @SuppressWarnings("squid:S1948") // Transient field used intentionally for non-serialized flow context
    private transient Map<String, Object> eventContext = new LinkedHashMap<>();

    /* ── Error/exception context (non-serialized) ────────────────── */
    @JsonIgnore
    @SuppressWarnings("squid:S1948") // Transient field used intentionally for error context
    private transient Throwable throwable;

    /* ── Event cursor for nested steps (legacy helpers) ──────────── */
    @JsonIgnore
    private final Deque<OEvent> eventStack = new ArrayDeque<>();

    /* ── Step emulation metadata (non-serialized) ────────────────── */
    @JsonIgnore
    @SuppressWarnings("squid:S1948") // Transient field used intentionally for step metadata
    private transient boolean step; // true if this context represents a promoted step

    @JsonIgnore
    @SuppressWarnings("squid:S1948") // Transient field used intentionally for timing calculations
    private transient long startNanoTime; // monotonic start for accurate duration when folding

    /** Full constructor (validates service id consistency). */
    public FlowEvent(
            String name,
            Instant timestamp,
            Long timeUnixNano,
            Instant endTimestamp,
            String traceId,
            String spanId,
            String parentSpanId,
            SpanKind kind,
            OResource resource,
            OAttributes attributes,
            List<OEvent> events,
            List<OLink> links,
            OStatus status,
            String serviceId,
            String correlationId,
            Boolean synthetic,
            Long elapsedNanos,
            Object returnValue,
            boolean returnValueCaptured) {

        this.name = name;
        this.timestamp = timestamp;
        this.timeUnixNano = timeUnixNano;
        this.endTimestamp = endTimestamp;
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.kind = kind;
        this.resource = resource;
        this.attributes = (attributes != null ? attributes : new OAttributes(new LinkedHashMap<>()));
        this.events = (events != null ? events : new ArrayList<>());
        this.links = (links != null ? links : new ArrayList<>());
        this.status = status;
        this.serviceId = serviceId;
        this.correlationId = correlationId;
        this.synthetic = synthetic;
        this.elapsedNanos = elapsedNanos;
        this.returnValueCaptured = returnValueCaptured;
        this.returnValue = returnValueCaptured ? returnValue : null;

        validateServiceIdConsistency();
        if (this.elapsedNanos == null || this.elapsedNanos < 0L) {
            recalculateElapsed();
        }
    }

    /* ========================= Accessors (record-like) ========================= */
    public String name() {
        return name;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public Long timeUnixNano() {
        return timeUnixNano;
    }

    public Instant endTimestamp() {
        return endTimestamp;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public SpanKind kind() {
        return kind;
    }

    public FlowEvent kind(SpanKind value) {
        this.kind = value;
        return this;
    }

    public FlowEvent trace(String traceId, String spanId, String parentSpanId) {
        if (traceId != null && !traceId.isBlank()) this.traceId = traceId;
        if (spanId != null && !spanId.isBlank()) this.spanId = spanId;
        if (parentSpanId != null && !parentSpanId.isBlank()) this.parentSpanId = parentSpanId;
        return this;
    }

    public OResource resource() {
        return resource;
    }

    public OAttributes attributes() {
        return attributes;
    }

    public List<OEvent> events() {
        return events;
    } // MUTABLE

    public List<OLink> links() {
        return links;
    } // MUTABLE

    public OStatus status() {
        return status;
    }

    public FlowEvent setStatus(OStatus status) {
        this.status = status;
        return this;
    }

    public String serviceId() {
        return serviceId;
    }

    public String correlationId() {
        return correlationId;
    }

    public Boolean synthetic() {
        return synthetic;
    }

    public boolean hasReturnValue() {
        return returnValueCaptured;
    }

    public Object returnValue() {
        return returnValue;
    }

    public FlowEvent setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
        this.returnValueCaptured = true;
        return this;
    }

    public FlowEvent clearReturnValue() {
        this.returnValue = null;
        this.returnValueCaptured = false;
        return this;
    }

    public Long elapsedNanos() {
        return elapsedNanos;
    }

    public FlowEvent setElapsedNanos(Long elapsedNanos) {
        if (elapsedNanos == null) {
            this.elapsedNanos = null;
        } else {
            this.elapsedNanos = Math.max(0L, elapsedNanos);
        }
        return this;
    }

    private void recalculateElapsed() {
        if (endTimestamp == null) {
            this.elapsedNanos = null;
            return;
        }
        Instant start = this.timestamp;
        if (start != null) {
            long nanos = Duration.between(start, endTimestamp).toNanos();
            this.elapsedNanos = nanos < 0L ? 0L : nanos;
            return;
        }
        if (timeUnixNano != null) {
            long endUnix = endTimestamp.getEpochSecond() * 1_000_000_000L + endTimestamp.getNano();
            long nanos = endUnix - timeUnixNano;
            this.elapsedNanos = nanos < 0L ? 0L : nanos;
            return;
        }
        this.elapsedNanos = null;
    }

    /** Flow-scoped EventContext (non-serialized). */
    @JsonIgnore
    public Map<String, Object> eventContext() {
        return eventContext;
    }

    @JsonIgnore
    public Map<String, Object> getEventContext() {
        return eventContext;
    }

    /** Step emulation metadata (non-serialized). */
    @JsonIgnore
    public boolean isStep() {
        return step;
    }

    @JsonIgnore
    public void setStep(boolean step) {
        this.step = step;
    }

    @JsonIgnore
    public long getStartNanoTime() {
        return startNanoTime;
    }

    @JsonIgnore
    public void setStartNanoTime(long startNanoTime) {
        this.startNanoTime = startNanoTime;
    }

    /* ===== Convenience getters for frameworks ===== */
    public String getName() {
        return name;
    }

    public SpanKind getSpanKind() {
        return kind;
    }

    public Object getReturnValue() {
        return returnValue;
    }

    public Long getElapsedNanos() {
        return elapsedNanos;
    }

    public void setEndTimestamp(Instant endTimestamp) {
        this.endTimestamp = endTimestamp;
        recalculateElapsed();
    }

    /* ========================= Throwable helpers ========================= */
    public Throwable throwable() {
        return throwable;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public FlowEvent setThrowable(Throwable throwable) {
        this.throwable = throwable;
        return this;
    }

    public boolean hasThrowable() {
        return throwable != null;
    }

    /* ========================= Attribute convenience ========================= */
    public boolean hasAttr(String key) {
        return key != null && attributes != null && attributes.map().containsKey(key);
    }

    public Object attrRaw(String key) {
        return hasAttr(key) ? attributes.map().get(key) : null;
    }

    @SuppressWarnings("unchecked")
    /**
     * Gets an attribute value converted to the specified type.
     * Supports automatic conversion between compatible types (Number, String, Boolean).
     *
     * @param key the attribute key
     * @param type the target type
     * @return the converted value, or null if attribute doesn't exist
     * @throws IllegalArgumentException if conversion is not possible
     */
    public <T> T attr(String key, Class<T> type) {
        Object v = attrRaw(key);
        if (v == null) return null;

        // Direct type match - no conversion needed
        if (type.isInstance(v)) return (T) v;

        // String conversion - always possible
        if (type == String.class) return (T) String.valueOf(v);

        // Delegate to type-specific converter
        return convertToType(key, v, type);
    }

    /**
     * Converts a value to the specified target type.
     * Delegates to type-specific conversion methods.
     */
    @SuppressWarnings("unchecked")
    private <T> T convertToType(String key, Object v, Class<T> type) {
        if (type == Boolean.class || type == boolean.class) {
            return (T) convertToBoolean(v);
        }
        if (type == Integer.class || type == int.class) {
            return (T) convertToInteger(v);
        }
        if (type == Long.class || type == long.class) {
            return (T) convertToLong(v);
        }
        if (type == Double.class || type == double.class) {
            return (T) convertToDouble(v);
        }
        if (type == Float.class || type == float.class) {
            return (T) convertToFloat(v);
        }
        if (type == Short.class || type == short.class) {
            return (T) convertToShort(v);
        }
        if (type == Byte.class || type == byte.class) {
            return (T) convertToByte(v);
        }

        throw new IllegalArgumentException("Attribute '" + key + "' is "
                + v.getClass().getName() + " and cannot be converted to " + type.getName());
    }

    /**
     * Converts a value to Boolean.
     * Supports Boolean, Number (0=false, non-zero=true), and String ("1", "0", "true", "false").
     */
    private Boolean convertToBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) {
            String trimmed = s.trim();
            if ("1".equals(trimmed)) return Boolean.TRUE;
            if ("0".equals(trimmed)) return Boolean.FALSE;
            return Boolean.parseBoolean(trimmed);
        }
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to Boolean");
    }

    /**
     * Converts a value to Integer.
     * Supports Number (with intValue()) and String (parsed).
     */
    private Integer convertToInteger(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to Integer");
    }

    /**
     * Converts a value to Long.
     * Supports Number (with longValue()) and String (parsed).
     */
    private Long convertToLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) return Long.parseLong(s);
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to Long");
    }

    /**
     * Converts a value to Double.
     * Supports Number (with doubleValue()) and String (parsed).
     */
    private Double convertToDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) return Double.parseDouble(s);
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to Double");
    }

    /**
     * Converts a value to Float.
     * Supports Number (with floatValue()) and String (parsed).
     */
    private Float convertToFloat(Object v) {
        if (v instanceof Number n) return n.floatValue();
        if (v instanceof String s) return Float.parseFloat(s);
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to Float");
    }

    /**
     * Converts a value to Short.
     * Supports Number (with shortValue()) and String (parsed).
     */
    private Short convertToShort(Object v) {
        if (v instanceof Number n) return n.shortValue();
        if (v instanceof String s) return Short.parseShort(s);
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to Short");
    }

    /**
     * Converts a value to Byte.
     * Supports Number (with byteValue()) and String (parsed).
     */
    private Byte convertToByte(Object v) {
        if (v instanceof Number n) return n.byteValue();
        if (v instanceof String s) return Byte.parseByte(s);
        throw new IllegalArgumentException("Cannot convert " + v.getClass().getName() + " to Byte");
    }

    public String attrAsString(String key) {
        Object v = attrRaw(key);
        return (v == null) ? null : String.valueOf(v);
    }

    public Long attrAsLong(String key) {
        if (!hasAttr(key)) return null;
        Object v = attributes.map().get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s)
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        return null;
    }

    public Integer attrAsInt(String key) {
        if (!hasAttr(key)) return null;
        Object v = attributes.map().get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s)
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        return null;
    }

    public Double attrAsDouble(String key) {
        if (!hasAttr(key)) return null;
        Object v = attributes.map().get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s)
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        return null;
    }

    public Boolean attrAsBoolean(String key) {
        if (!hasAttr(key)) return null;
        Object v = attributes.map().get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) {
            if ("1".equals(s)) return Boolean.TRUE;
            if ("0".equals(s)) return Boolean.FALSE;
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    public Map<String, String> stringAttributes() {
        Map<String, Object> src = (attributes != null ? attributes.map() : new LinkedHashMap<>());
        Map<String, String> out = new LinkedHashMap<>(src.size());
        for (Map.Entry<String, Object> e : src.entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v == null ? null : String.valueOf(v));
        }
        return out;
    }

    /* ========================= EventContext helpers ========================= */
    public void eventContextPut(final String key, final Object value) {
        if (key == null || key.isBlank()) return;
        final OEvent currentEvent = eventStack.peekLast();
        if (currentEvent != null) currentEvent.eventContext().put(key, value);
        else eventContext().put(key, value);
    }

    public Object eventContextGet(final String key) {
        final OEvent currentEvent = eventStack.peekLast();
        return (currentEvent != null)
                ? currentEvent.eventContext().get(key)
                : eventContext().get(key);
    }

    public boolean hasEventContextKey(final String key) {
        final OEvent currentEvent = eventStack.peekLast();
        if (currentEvent != null && currentEvent.eventContext().containsKey(key)) return true;
        return eventContext().containsKey(key);
    }

    /* ========================= Legacy step lifecycle helpers ========================= */
    public OEvent beginStepEvent(
            final String name, final long epochNanos, final long startNanoTime, final OAttributes initialAttrs) {
        return beginStepEvent(name, epochNanos, startNanoTime, initialAttrs, null);
    }

    public OEvent beginStepEvent(
            final String name,
            final long epochNanos,
            final long startNanoTime,
            final OAttributes initialAttrs,
            final String kind) {
        final OAttributes attrs = (initialAttrs != null) ? initialAttrs : new OAttributes(new LinkedHashMap<>());
        final OEvent ev = new OEvent(
                name,
                epochNanos,
                null,
                attrs,
                0,
                startNanoTime,
                new LinkedHashMap<>(),
                new OStatus(StatusCode.UNSET, null),
                null);
        ev.setKind(kind);
        if (!eventStack.isEmpty()) {
            eventStack.peekLast().ensureEvents().add(ev);
        } else {
            events().add(ev);
        }
        eventStack.addLast(ev);
        return ev;
    }

    public void endStepEvent(final long endEpochNanos, final long endNanoTime, final Map<String, Object> updates) {
        final OEvent ev = eventStack.pollLast();
        if (ev == null) return;

        final OAttributes attrs = ev.attributes();
        if (updates != null && !updates.isEmpty()) updates.forEach(attrs::put);

        final long start = ev.getStartNanoTime();
        final long duration = (start > 0L && endNanoTime > 0L) ? (endNanoTime - start) : 0L;
        attrs.put("duration.nanos", duration);
        ev.setEndEpochNanos(endEpochNanos);
        if (updates != null && updates.containsKey("error")) {
            String msg = String.valueOf(updates.get("error"));
            ev.setStatus(new OStatus(StatusCode.ERROR, msg));
        } else {
            ev.setStatus(new OStatus(StatusCode.OK, null));
        }
    }

    /* ========================= Service Id ========================= */
    public String effectiveServiceId() {
        if (serviceId != null && !serviceId.isBlank()) return serviceId;
        if (resource == null || resource.attributes() == null) return null;
        Object v = resource.attributes().map().get(SERVICE_ID_ATTR);
        return v == null ? null : String.valueOf(v);
    }

    private void validateServiceIdConsistency() {
        String top = (serviceId == null || serviceId.isBlank()) ? null : serviceId;
        String fromRes = null;
        if (resource != null && resource.attributes() != null) {
            Object v = resource.attributes().map().get(SERVICE_ID_ATTR);
            if (v != null && !String.valueOf(v).isBlank()) fromRes = String.valueOf(v);
        }
        if (top == null && fromRes == null) {
            throw new IllegalArgumentException(
                    "Missing service identifier: set top-level 'serviceId' or resource.attributes[\"service.id\"]");
        }
        if (top != null && fromRes != null && !top.equals(fromRes)) {
            throw new IllegalArgumentException(
                    "Conflicting service identifiers: top-level 'serviceId' != resource.attributes[\"service.id\"]");
        }
    }
}

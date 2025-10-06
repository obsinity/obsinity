package com.obsinity.service.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical, storage-level envelope for an ingested event.
 * Uses Map<String,Object> for nested JSON payloads.
 *
 * JSON view aligns with the canonical structure:
 *  - time: { startedAt, endUnixNano, ... }
 *  - event: { name (event type), kind }
 *  - trace: { traceId, spanId, parentSpanId }
 *  - resource: <map>
 *  - attributes: <map>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class EventEnvelope {

    // -------- Routing / identity
    private final String serviceId; // from URL
    private final String eventType; // from URL
    private final String eventId; // from body (UUIDv7 recommended)

    // -------- Timing
    private final Instant timestamp; // start/startedAt (body)
    private final Instant endTimestamp; // optional end time for span-like events
    private final Instant ingestedAt; // set by server at write time

    // -------- OTEL span surface (optional)
    private final String name; // Span.name (exposed under event object only for kind; event name uses eventType)
    private final String kind; // INTERNAL | SERVER | CLIENT | PRODUCER | CONSUMER

    // Trace correlation (flattened)
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;

    // Status (optional)
    private final Status status; // UNSET | OK | ERROR (+ message)

    // Resource attributes (nested JSON object)
    private final Map<String, Object> resourceAttributes;

    // Business/telemetry attributes (nested JSON object)
    private final Map<String, Object> attributes;

    // Span events/links (optional)
    private final List<OtelEvent> events;
    private final List<OtelLink> links;

    // Misc
    private final String correlationId;
    private final Boolean synthetic;

    // ---------- Builder

    private EventEnvelope(Builder b) {
        this.serviceId = Objects.requireNonNull(b.serviceId, "serviceId");
        this.eventType = Objects.requireNonNull(b.eventType, "eventType");
        this.eventId = Objects.requireNonNull(b.eventId, "eventId");
        this.timestamp = Objects.requireNonNull(b.timestamp, "timestamp");
        this.endTimestamp = b.endTimestamp;
        this.ingestedAt = Objects.requireNonNull(b.ingestedAt, "ingestedAt");
        this.name = b.name;
        this.kind = b.kind;
        this.traceId = b.traceId;
        this.spanId = b.spanId;
        this.parentSpanId = b.parentSpanId;
        this.status = b.status;
        this.resourceAttributes = b.resourceAttributes;
        this.attributes = Objects.requireNonNull(b.attributes, "attributes");
        this.events = b.events;
        this.links = b.links;
        this.correlationId = b.correlationId;
        this.synthetic = b.synthetic;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String serviceId;
        private String eventType;
        private String eventId;
        private Instant timestamp;
        private Instant endTimestamp;
        private Instant ingestedAt;
        private String name;
        private String kind;
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private Status status;
        private Map<String, Object> resourceAttributes;
        private Map<String, Object> attributes;
        private List<OtelEvent> events;
        private List<OtelLink> links;
        private String correlationId;
        private Boolean synthetic;

        public Builder serviceId(String v) {
            this.serviceId = v;
            return this;
        }

        public Builder eventType(String v) {
            this.eventType = v;
            return this;
        }

        public Builder eventId(String v) {
            this.eventId = v;
            return this;
        }

        public Builder timestamp(Instant v) {
            this.timestamp = v;
            return this;
        }

        public Builder endTimestamp(Instant v) {
            this.endTimestamp = v;
            return this;
        }

        public Builder ingestedAt(Instant v) {
            this.ingestedAt = v;
            return this;
        }

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder kind(String v) {
            this.kind = v;
            return this;
        }

        public Builder traceId(String v) {
            this.traceId = v;
            return this;
        }

        public Builder spanId(String v) {
            this.spanId = v;
            return this;
        }

        public Builder parentSpanId(String v) {
            this.parentSpanId = v;
            return this;
        }

        public Builder status(Status v) {
            this.status = v;
            return this;
        }

        public Builder resourceAttributes(Map<String, Object> v) {
            this.resourceAttributes = v;
            return this;
        }

        public Builder attributes(Map<String, Object> v) {
            this.attributes = v;
            return this;
        }

        public Builder events(List<OtelEvent> v) {
            this.events = v;
            return this;
        }

        public Builder links(List<OtelLink> v) {
            this.links = v;
            return this;
        }

        public Builder correlationId(String v) {
            this.correlationId = v;
            return this;
        }

        public Builder synthetic(Boolean v) {
            this.synthetic = v;
            return this;
        }

        public EventEnvelope build() {
            return new EventEnvelope(this);
        }
    }

    // ---------- Nested types

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Status {
        private final String code; // UNSET | OK | ERROR
        private final String message;

        public Status(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class OtelEvent {
        private final String name;
        private final Instant timestamp;
        private final Instant endTimestamp;
        private final Long timeUnixNano;
        private final Long endUnixNano;
        private final String kind;
        private final Map<String, Object> attributes;
        private final List<OtelEvent> events;
        private final Status status;

        public OtelEvent(
                String name,
                Instant timestamp,
                Instant endTimestamp,
                Long timeUnixNano,
                Long endUnixNano,
                String kind,
                Map<String, Object> attributes,
                List<OtelEvent> events,
                Status status) {
            this.name = name;
            this.timestamp = timestamp;
            this.endTimestamp = endTimestamp;
            this.timeUnixNano = timeUnixNano;
            this.endUnixNano = endUnixNano;
            this.kind = kind;
            this.attributes = attributes;
            this.events = events;
            this.status = status;
        }

        public String getName() {
            return name;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public Instant getEndTimestamp() {
            return endTimestamp;
        }

        public Long getTimeUnixNano() {
            return timeUnixNano;
        }

        public Long getEndUnixNano() {
            return endUnixNano;
        }

        public String getKind() {
            return kind;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

        public List<OtelEvent> getEvents() {
            return events;
        }

        public Status getStatus() {
            return status;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class OtelLink {
        private final String traceId;
        private final String spanId;
        private final Map<String, Object> attributes;

        public OtelLink(String traceId, String spanId, Map<String, Object> attributes) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.attributes = attributes;
        }

        public String getTraceId() {
            return traceId;
        }

        public String getSpanId() {
            return spanId;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }
    }

    // ---------- JSON view helpers ----------

    /**
     * Expose the OTEL-aligned time block for JSON serialization.
     */
    @JsonProperty("time")
    public TimeBlock jsonTime() {
        return new TimeBlock(timestamp, endTimestamp, null, null);
    }

    /** Expose event object with event type as name and span kind. */
    @JsonProperty("event")
    public EventObj jsonEvent() {
        return new EventObj(eventType, kind);
    }

    /** Expose trace object. */
    @JsonProperty("trace")
    public TraceObj jsonTrace() {
        if (traceId == null && spanId == null && parentSpanId == null) return null;
        return new TraceObj(traceId, spanId, parentSpanId);
    }

    /** Expose resource map under 'resource'. */
    @JsonProperty("resource")
    public Map<String, Object> jsonResource() {
        return resourceAttributes;
    }

    // helper DTOs for JSON view
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class TimeBlock {
        public final Instant startedAt;
        public final Instant endedAt;
        public final Long startUnixNano;
        public final Long endUnixNano;

        public TimeBlock(Instant startedAt, Instant endedAt, Long startUnixNano, Long endUnixNano) {
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.startUnixNano = startUnixNano;
            this.endUnixNano = endUnixNano;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class EventObj {
        public final String name; // event type
        public final String kind;

        public EventObj(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class TraceObj {
        public final String traceId;
        public final String spanId;
        public final String parentSpanId;

        public TraceObj(String traceId, String spanId, String parentSpanId) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
        }
    }

    // ---------- Getters

    public String getServiceId() {
        return serviceId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventId() {
        return eventId;
    }

    @JsonIgnore
    public Instant getTimestamp() {
        return timestamp;
    }

    @JsonIgnore
    public Instant getEndTimestamp() {
        return endTimestamp;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    @JsonIgnore
    public String getName() {
        return name;
    }

    @JsonIgnore
    public String getKind() {
        return kind;
    }

    @JsonIgnore
    public String getTraceId() {
        return traceId;
    }

    @JsonIgnore
    public String getSpanId() {
        return spanId;
    }

    @JsonIgnore
    public String getParentSpanId() {
        return parentSpanId;
    }

    public Status getStatus() {
        return status;
    }

    @JsonIgnore
    public Map<String, Object> getResourceAttributes() {
        return resourceAttributes;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public List<OtelEvent> getEvents() {
        return events;
    }

    public List<OtelLink> getLinks() {
        return links;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Boolean getSynthetic() {
        return synthetic;
    }

    // ---------- Equality & debug

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventEnvelope that)) return false;
        return Objects.equals(serviceId, that.serviceId)
                && Objects.equals(eventType, that.eventType)
                && Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceId, eventType, eventId);
    }

    @Override
    public String toString() {
        return "EventEnvelope{" + "serviceId='"
                + serviceId + '\'' + ", eventType='"
                + eventType + '\'' + ", eventId='"
                + eventId + '\'' + ", timestamp="
                + timestamp + ", endTimestamp="
                + endTimestamp + ", ingestedAt="
                + ingestedAt + ", kind='"
                + kind + '\'' + ", traceId='"
                + traceId + '\'' + '}';
    }
}

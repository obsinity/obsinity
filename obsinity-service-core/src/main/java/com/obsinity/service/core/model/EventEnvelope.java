package com.obsinity.service.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical, storage-level envelope for an ingested event.
 * Uses Map<String,Object> for nested JSON payloads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class EventEnvelope {

    // -------- Routing / identity
    private final String serviceId; // from URL
    private final String eventType; // from URL
    private final String eventId; // from body (ULID/UUIDv7 recommended)

    // -------- Timing
    private final Instant timestamp; // start/occurredAt (body)
    private final Instant endTimestamp; // optional end time for span-like events
    private final Instant ingestedAt; // set by server at write time

    // -------- OTEL span surface (optional)
    private final String name; // Span.name
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
        private final Map<String, Object> attributes;

        public OtelEvent(String name, Instant timestamp, Map<String, Object> attributes) {
            this.name = name;
            this.timestamp = timestamp;
            this.attributes = attributes;
        }

        public String getName() {
            return name;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
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

    public Instant getTimestamp() {
        return timestamp;
    }

    public Instant getEndTimestamp() {
        return endTimestamp;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public Status getStatus() {
        return status;
    }

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

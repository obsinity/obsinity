package com.obsinity.service.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.obsinity.telemetry.model.OAttributes;
import com.obsinity.telemetry.model.OEvent;
import com.obsinity.telemetry.model.OLink;
import com.obsinity.telemetry.model.OResource;
import com.obsinity.telemetry.model.OStatus;
import com.obsinity.telemetry.model.TelemetryHolder;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@JsonInclude(Include.NON_NULL)
public class EventEnvelope {

    /* ── New: stable event id ─────────────────────────── */
    private String eventId;

    /* ── OTEL-ish core (matches TelemetryHolder wire shape) ───────────────── */
    private String name;
    private Instant timestamp; // keep as canonical time (no "ts" field)
    private Long timeUnixNano;
    private Instant endTimestamp;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private SpanKind kind;
    private OResource resource;
    private OAttributes attributes;
    private List<OEvent> events;
    private List<OLink> links;
    private OStatus status;

    /* ── Obsinity-native ─────────────────────────────────────────────────── */
    private String serviceId; // tenantId == serviceId (see shim getter below)
    private String correlationId;
    private Boolean synthetic;

    public EventEnvelope() {}

    /* -------------------- Builder -------------------- */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String eventId;
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

        private Builder() {}

        public Builder eventId(String v) {
            this.eventId = v;
            return this;
        }

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder timestamp(Instant v) {
            this.timestamp = v;
            return this;
        }

        public Builder timeUnixNano(Long v) {
            this.timeUnixNano = v;
            return this;
        }

        public Builder endTimestamp(Instant v) {
            this.endTimestamp = v;
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

        public Builder kind(SpanKind v) {
            this.kind = v;
            return this;
        }

        public Builder resource(OResource v) {
            this.resource = v;
            return this;
        }

        public Builder attributes(OAttributes v) {
            this.attributes = v;
            return this;
        }

        public Builder putAttribute(String k, Object val) {
            if (this.attributes == null) this.attributes = new OAttributes(new LinkedHashMap<>());
            this.attributes.put(k, val);
            return this;
        }

        public Builder events(List<OEvent> v) {
            this.events = v;
            return this;
        }

        public Builder addEvent(OEvent v) {
            this.events.add(v);
            return this;
        }

        public Builder links(List<OLink> v) {
            this.links = v;
            return this;
        }

        public Builder addLink(OLink v) {
            this.links.add(v);
            return this;
        }

        public Builder status(OStatus v) {
            this.status = v;
            return this;
        }

        public Builder serviceId(String v) {
            this.serviceId = v;
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
            EventEnvelope e = new EventEnvelope();
            e.eventId = this.eventId;
            e.name = this.name;
            e.timestamp = this.timestamp;
            e.timeUnixNano = this.timeUnixNano;
            e.endTimestamp = this.endTimestamp;
            e.traceId = this.traceId;
            e.spanId = this.spanId;
            e.parentSpanId = this.parentSpanId;
            e.kind = this.kind;
            e.resource = this.resource;
            e.attributes = this.attributes != null ? this.attributes : new OAttributes(new LinkedHashMap<>());
            e.events = this.events != null ? this.events : new ArrayList<>();
            e.links = this.links != null ? this.links : new ArrayList<>();
            e.status = this.status;
            e.serviceId = this.serviceId;
            e.correlationId = this.correlationId;
            e.synthetic = this.synthetic;
            return e;
        }
    }

    /* -------------------- Getters / Setters (Jackson) -------------------- */
    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Long getTimeUnixNano() {
        return timeUnixNano;
    }

    public void setTimeUnixNano(Long timeUnixNano) {
        this.timeUnixNano = timeUnixNano;
    }

    public Instant getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(Instant endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public void setParentSpanId(String parentSpanId) {
        this.parentSpanId = parentSpanId;
    }

    public SpanKind getKind() {
        return kind;
    }

    public void setKind(SpanKind kind) {
        this.kind = kind;
    }

    public OResource getResource() {
        return resource;
    }

    public void setResource(OResource resource) {
        this.resource = resource;
    }

    public OAttributes getAttributes() {
        return attributes;
    }

    public void setAttributes(OAttributes attributes) {
        this.attributes = attributes;
    }

    public List<OEvent> getEvents() {
        return events;
    }

    public void setEvents(List<OEvent> events) {
        this.events = events;
    }

    public List<OLink> getLinks() {
        return links;
    }

    public void setLinks(List<OLink> links) {
        this.links = links;
    }

    public OStatus getStatus() {
        return status;
    }

    public void setStatus(OStatus status) {
        this.status = status;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Boolean getSynthetic() {
        return synthetic;
    }

    public void setSynthetic(Boolean synthetic) {
        this.synthetic = synthetic;
    }

    /* -------------------- Convenience helpers -------------------- */
    public void putResourceAttribute(String key, Object value) {
        if (resource == null) resource = new OResource(new OAttributes(new LinkedHashMap<>()));
        if (resource.attributes() == null) resource = new OResource(new OAttributes(new LinkedHashMap<>()));
        resource.attributes().put(key, value);
    }

    public void putAttribute(String key, Object value) {
        if (attributes == null) attributes = new OAttributes(new LinkedHashMap<>());
        attributes.put(key, value);
    }

    /** Optional: ensure resource.attributes["service.id"] mirrors top-level serviceId. */
    public void synchronizeServiceIdToResource() {
        if (serviceId == null || serviceId.isBlank()) return;
        putResourceAttribute(TelemetryHolder.SERVICE_ID_ATTR, serviceId);
    }

    /* -------------------- Mapping to/from TelemetryHolder -------------------- */
    public static EventEnvelope fromHolder(TelemetryHolder h) {
        if (h == null) return null;
        EventEnvelope e = new EventEnvelope();
        // choose spanId as default event id, if present
        e.eventId = (h.spanId() != null && !h.spanId().isBlank()) ? h.spanId() : h.traceId();
        e.name = h.name();
        e.timestamp = h.timestamp();
        e.timeUnixNano = h.timeUnixNano();
        e.endTimestamp = h.endTimestamp();
        e.traceId = h.traceId();
        e.spanId = h.spanId();
        e.parentSpanId = h.parentSpanId();
        e.kind = h.kind();
        e.resource = h.resource();
        e.attributes = h.attributes();
        e.events = h.events();
        e.links = h.links();
        e.status = h.status();
        e.serviceId = h.serviceId();
        e.correlationId = h.correlationId();
        e.synthetic = h.synthetic();
        return e;
    }

    public TelemetryHolder toHolder() {
        // TelemetryHolder will validate service id consistency
        return new TelemetryHolder(
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
                synthetic);
    }
}

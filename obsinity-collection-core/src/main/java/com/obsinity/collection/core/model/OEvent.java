package com.obsinity.collection.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collection-side telemetry event model aligned with design/basic-structure.json.
 *
 * Notes:
 * - Convenience getters (startedAt, name, attributes, context) are provided for existing code.
 * - Use Builder to construct instances; all nested objects are optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class OEvent {
    private final Time time; // start/end times (ISO + nanos)
    private final Event event; // identity (name/kind)
    private final Resource resource; // service/host/telemetry/cloud (+ context)
    private final Trace trace; // trace/span IDs
    private final Map<String, Object> attributes; // business attributes
    private final List<SpanEvent> events; // nested events
    private final List<Link> links; // links to other spans
    private final Status status; // OTEL-style status
    private final Boolean synthetic; // Obsinity-native flag

    private OEvent(Builder b) {
        this.time = b.time;
        this.event = b.event;
        this.resource = b.resource;
        this.trace = b.trace;
        this.attributes = unmodifiableMap(b.attributes);
        this.events = unmodifiableList(b.events);
        this.links = unmodifiableList(b.links);
        this.status = b.status;
        this.synthetic = b.synthetic;
    }

    private static Map<String, Object> unmodifiableMap(Map<String, Object> m) {
        if (m == null) return null;
        return Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    private static <T> List<T> unmodifiableList(List<T> l) {
        if (l == null) return null;
        return Collections.unmodifiableList(new ArrayList<>(l));
    }

    // ---- Convenience accessors for existing code ----
    public Instant startedAt() {
        return (time == null) ? null : time.startedAt;
    }

    @Deprecated(forRemoval = true)
    public Instant occurredAt() {
        return startedAt();
    }

    public String name() {
        return (event == null) ? null : event.name;
    }

    public Map<String, Object> attributes() {
        return attributes == null ? Map.of() : attributes;
    }

    public Map<String, Object> context() {
        return (resource == null || resource.context == null) ? Map.of() : resource.context;
    }

    // ---- Full model accessors ----
    public Time time() {
        return time;
    }

    public Event event() {
        return event;
    }

    public Resource resource() {
        return resource;
    }

    public Trace trace() {
        return trace;
    }

    public List<SpanEvent> events() {
        return events;
    }

    public List<Link> links() {
        return links;
    }

    public Status status() {
        return status;
    }

    public Boolean synthetic() {
        return synthetic;
    }

    // ---- Builder ----
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Time time;
        private Event event;
        private Resource resource;
        private Trace trace;
        private Map<String, Object> attributes;
        private List<SpanEvent> events;
        private List<Link> links;
        private Status status;
        private Boolean synthetic;

        public Builder startedAt(Instant ts) {
            if (this.time == null) this.time = new Time();
            this.time.startedAt = ts;
            return this;
        }

        @Deprecated(forRemoval = true)
        public Builder occurredAt(Instant ts) {
            return startedAt(ts);
        }

        public Builder endedAt(Instant ts) {
            if (this.time == null) this.time = new Time();
            this.time.endedAt = ts;
            return this;
        }

        public Builder name(String n) {
            if (this.event == null) this.event = new Event();
            this.event.name = n;
            return this;
        }

        public Builder kind(String k) {
            if (this.event == null) this.event = new Event();
            this.event.kind = k;
            return this;
        }

        public Builder serviceName(String name) {
            if (this.resource == null) this.resource = new Resource();
            if (this.resource.service == null) this.resource.service = new Resource.Service();
            this.resource.service.name = name;
            return this;
        }

        public Builder resourceContext(Map<String, Object> ctx) {
            if (this.resource == null) this.resource = new Resource();
            this.resource.context = ctx;
            return this;
        }

        public Builder attributes(Map<String, Object> attrs) {
            this.attributes = attrs;
            return this;
        }

        public Builder status(String code, String message) {
            this.status = new Status(code, message);
            return this;
        }

        public Builder trace(String traceId, String spanId, String parentSpanId, String state) {
            if (this.trace == null) this.trace = new Trace();
            this.trace.traceId = traceId;
            this.trace.spanId = spanId;
            this.trace.parentSpanId = parentSpanId;
            this.trace.state = state;
            return this;
        }

        public Builder serviceNamespace(String ns) {
            if (this.resource == null) this.resource = new Resource();
            if (this.resource.service == null) this.resource.service = new Resource.Service();
            this.resource.service.namespace = ns;
            return this;
        }

        public Builder serviceVersion(String ver) {
            if (this.resource == null) this.resource = new Resource();
            if (this.resource.service == null) this.resource.service = new Resource.Service();
            this.resource.service.version = ver;
            return this;
        }

        public Builder serviceInstanceId(String id) {
            if (this.resource == null) this.resource = new Resource();
            if (this.resource.service == null) this.resource.service = new Resource.Service();
            if (this.resource.service.instance == null)
                this.resource.service.instance = new Resource.Service.Instance();
            this.resource.service.instance.id = id;
            return this;
        }

        public Builder telemetrySdk(String name, String version) {
            if (this.resource == null) this.resource = new Resource();
            if (this.resource.telemetry == null) this.resource.telemetry = new Resource.FlowTelemetry();
            if (this.resource.telemetry.sdk == null) this.resource.telemetry.sdk = new Resource.FlowTelemetry.Sdk();
            this.resource.telemetry.sdk.name = name;
            this.resource.telemetry.sdk.version = version;
            return this;
        }

        public Builder hostName(String name) {
            if (this.resource == null) this.resource = new Resource();
            if (this.resource.host == null) this.resource.host = new Resource.Host();
            this.resource.host.name = name;
            return this;
        }

        public Builder cloud(String provider, String region) {
            if (this.resource == null) this.resource = new Resource();
            if (this.resource.cloud == null) this.resource.cloud = new Resource.Cloud();
            this.resource.cloud.provider = provider;
            this.resource.cloud.region = region;
            return this;
        }

        public Builder startUnixNano(Long nanos) {
            if (this.time == null) this.time = new Time();
            this.time.startUnixNano = nanos;
            return this;
        }

        public Builder endUnixNano(Long nanos) {
            if (this.time == null) this.time = new Time();
            this.time.endUnixNano = nanos;
            return this;
        }

        public OEvent build() {
            return new OEvent(this);
        }
    }

    // ---- Nested model types ----
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Time {
        public Long startUnixNano;
        public Long endUnixNano;
        public Instant startedAt;
        public Instant endedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Event {
        public String name;
        public String kind;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Resource {
        public Service service;
        public Host host;
        public FlowTelemetry telemetry;
        public Cloud cloud;
        public Map<String, Object> context; // additional resource-scoped context

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static final class Service {
            public String name;
            public String namespace;
            public Instance instance;
            public String version;

            @JsonInclude(JsonInclude.Include.NON_NULL)
            public static final class Instance {
                public String id;
            }
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static final class Host {
            public String name;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static final class FlowTelemetry {
            public Sdk sdk;

            @JsonInclude(JsonInclude.Include.NON_NULL)
            public static final class Sdk {
                public String name;
                public String version;
            }
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static final class Cloud {
            public String provider;
            public String region;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Trace {
        public String traceId;
        public String spanId;
        public String parentSpanId;
        public String state; // tracestate
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class SpanEvent {
        public String name;
        public Long timeUnixNano;
        public Map<String, Object> attributes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Link {
        public String traceId;
        public String spanId;
        public Map<String, Object> attributes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Status {
        public final String code; // UNSET | OK | ERROR
        public final String message;

        public Status(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}

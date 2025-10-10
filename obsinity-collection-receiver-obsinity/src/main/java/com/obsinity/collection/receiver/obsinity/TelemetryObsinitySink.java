package com.obsinity.collection.receiver.obsinity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.obsinity.client.transport.EventSender;
import com.obsinity.collection.api.annotations.FlowSink;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.telemetry.model.FlowEvent;
import com.obsinity.telemetry.model.OResource;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FlowSink
public class TelemetryObsinitySink {
    private static final Logger log = LoggerFactory.getLogger(TelemetryObsinitySink.class);
    private static final String SERVICE_PROP = "obsinity.collection.service";
    private static final String SERVICE_ENV = "OBSINITY_SERVICE";
    private static final String DUMMY_SERVICE_ID = "**DUMMY-SERVICE-ID**";

    private final EventSender sender;
    private final ObjectMapper json;
    private final String configuredServiceId;

    public TelemetryObsinitySink(EventSender sender) {
        this(sender, null);
    }

    public TelemetryObsinitySink(EventSender sender, String configuredServiceId) {
        this.sender = sender;
        this.configuredServiceId = sanitize(configuredServiceId);
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @OnFlowStarted
    public void onStarted(FlowEvent event) {
        // Ignore STARTED lifecycle; we only emit terminal events.
    }

    @OnFlowCompleted
    public void onCompleted(FlowEvent event) throws IOException {
        ensureEndTimestamp(event);
        send(event);
    }

    @OnFlowFailure
    public void onFailed(FlowEvent event) throws IOException {
        ensureEndTimestamp(event);
        send(event);
    }

    private void ensureEndTimestamp(FlowEvent event) {
        if (event.endTimestamp() == null) {
            event.setEndTimestamp(Instant.now());
        }
    }

    private void send(FlowEvent event) throws IOException {
        byte[] body = json.writeValueAsBytes(toUnifiedPublishBody(event, configuredServiceId));
        sender.send(body);
    }

    static Map<String, Object> toUnifiedPublishBody(FlowEvent event, String configuredServiceId) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> eventNode = new LinkedHashMap<>();
        eventNode.put("name", event.name());
        if (event.kind() != null) eventNode.put("kind", event.kind().name());
        root.put("event", eventNode);

        root.put("resource", buildResource(event, configuredServiceId));

        Map<String, Object> trace = new LinkedHashMap<>();
        if (event.traceId() != null) trace.put("traceId", event.traceId());
        if (event.spanId() != null) trace.put("spanId", event.spanId());
        if (!trace.isEmpty()) {
            root.put("trace", trace);
        }

        Map<String, Object> time = new LinkedHashMap<>();
        Instant startedAt = event.timestamp();
        if (startedAt != null) {
            time.put("startedAt", startedAt);
        }
        Long startUnix = event.timeUnixNano();
        if (startUnix == null && startedAt != null) {
            startUnix = toUnixNanos(startedAt);
        }
        if (startUnix != null) {
            time.put("startUnixNano", startUnix);
        }

        Instant endedAt = event.endTimestamp();
        if (endedAt != null) {
            time.put("endedAt", endedAt);
            Long endUnix = toUnixNanos(endedAt);
            if (endUnix != null) {
                time.put("endUnixNano", endUnix);
            } else if (startUnix != null) {
                long nanos = Duration.between(startedAt, endedAt).toNanos();
                time.put("endUnixNano", startUnix + nanos);
            }
        }
        if (!time.isEmpty()) {
            root.put("time", time);
        }

        if (event.status() != null
                && (event.status().getCode() != null || event.status().getMessage() != null)) {
            Map<String, Object> status = new LinkedHashMap<>();
            if (event.status().getCode() != null)
                status.put("code", String.valueOf(event.status().getCode()));
            if (event.status().getMessage() != null)
                status.put("message", event.status().getMessage());
            root.put("status", status);
        }

        Map<String, Object> attributes = new LinkedHashMap<>(
                event.attributes() == null ? Map.of() : event.attributes().map());
        root.put("attributes", attributes);

        List<Map<String, Object>> eventsSerialized = serializeEvents(event.events());
        List<?> links = event.links() == null ? List.of() : event.links();
        root.put("events", eventsSerialized);
        root.put("links", links);
        root.put("synthetic", event.synthetic() != null ? event.synthetic() : Boolean.FALSE);

        return root;
    }

    private static Map<String, Object> buildResource(FlowEvent event, String configuredServiceId) {
        Map<String, Object> resource = new LinkedHashMap<>();

        Map<String, Object> service = new LinkedHashMap<>();
        String serviceId = sanitize(event.effectiveServiceId());
        if (serviceId == null) serviceId = sanitize(configuredServiceId);
        if (serviceId == null) serviceId = sanitize(System.getProperty(SERVICE_PROP));
        if (serviceId == null) serviceId = sanitize(System.getenv(SERVICE_ENV));
        if (serviceId != null) {
            service.put("name", serviceId);
        } else {
            throw new IllegalStateException("Missing service identifier for telemetry event '"
                    + event.name()
                    + "'. Configure system property '"
                    + SERVICE_PROP
                    + "' or environment variable '"
                    + SERVICE_ENV
                    + "'.");
        }

        Map<String, Object> attrs =
                event.attributes() == null ? Map.of() : event.attributes().map();

        String namespace = attribute(attrs, "service.namespace");
        if (namespace != null) service.put("namespace", namespace);
        String version = attribute(attrs, "service.version");
        if (version != null) service.put("version", version);
        String instanceId = attribute(attrs, "service.instance.id");
        if (instanceId != null) {
            Map<String, Object> instance = new LinkedHashMap<>();
            instance.put("id", instanceId);
            service.put("instance", instance);
        }

        Map<String, Object> rawService = nestedMap(attrs, "service");
        if (rawService != null) {
            service.putAll(rawService);
        }

        if (!service.isEmpty()) {
            resource.put("service", service);
        }

        String hostName = attribute(attrs, "host.name");
        if (hostName != null) {
            resource.put("host", Map.of("name", hostName));
        } else {
            Map<String, Object> rawHost = nestedMap(attrs, "host");
            if (rawHost != null && !rawHost.isEmpty()) resource.put("host", rawHost);
        }

        String provider = attribute(attrs, "cloud.provider");
        String region = attribute(attrs, "cloud.region");
        Map<String, Object> cloud = provider != null || region != null ? new LinkedHashMap<>() : null;
        if (cloud != null) {
            if (provider != null) cloud.put("provider", provider);
            if (region != null) cloud.put("region", region);
            resource.put("cloud", cloud);
        } else {
            Map<String, Object> rawCloud = nestedMap(attrs, "cloud");
            if (rawCloud != null && !rawCloud.isEmpty()) resource.put("cloud", rawCloud);
        }

        String sdkName = attribute(attrs, "telemetry.sdk.name");
        String sdkVersion = attribute(attrs, "telemetry.sdk.version");
        if (sdkName != null || sdkVersion != null) {
            Map<String, Object> sdk = new LinkedHashMap<>();
            if (sdkName != null) sdk.put("name", sdkName);
            if (sdkVersion != null) sdk.put("version", sdkVersion);
            resource.put("telemetry", Map.of("sdk", sdk));
        } else {
            Map<String, Object> telemetry = nestedMap(attrs, "telemetry");
            if (telemetry != null && !telemetry.isEmpty()) resource.put("telemetry", telemetry);
        }

        OResource oResource = event.resource();
        if (oResource != null
                && oResource.attributes() != null
                && oResource.attributes().map() != null) {
            Map<String, Object> raw = oResource.attributes().map();
            mergeNestedMap(raw.get("service"), service);
            Map<String, Object> rawHost = nestedMap(raw, "host");
            if (rawHost != null && !rawHost.isEmpty()) resource.put("host", rawHost);
            Map<String, Object> rawCloud = nestedMap(raw, "cloud");
            if (rawCloud != null && !rawCloud.isEmpty()) resource.put("cloud", rawCloud);
            Map<String, Object> rawTelemetry = nestedMap(raw, "telemetry");
            if (rawTelemetry != null && !rawTelemetry.isEmpty()) resource.put("telemetry", rawTelemetry);
        }

        Map<String, Object> context = event.eventContext();
        if (context != null && !context.isEmpty()) {
            resource.put("context", context);
        }

        return resource;
    }

    private static List<Map<String, Object>> serializeEvents(List<com.obsinity.telemetry.model.OEvent> events) {
        if (events == null || events.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(events.size());
        for (com.obsinity.telemetry.model.OEvent e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getName());
            Map<String, Object> time = new LinkedHashMap<>();
            long epochNanos = e.getEpochNanos();
            if (epochNanos > 0) {
                time.put("startUnixNano", epochNanos);
                time.put("startedAt", instantFromNanos(epochNanos));
            }
            Long endEpochNanos = e.getEndEpochNanos();
            if (endEpochNanos != null && endEpochNanos > 0) {
                time.put("endUnixNano", endEpochNanos);
                time.put("endedAt", instantFromNanos(endEpochNanos));
            }
            if (!time.isEmpty()) {
                m.put("time", time);
            }
            if (e.getKind() != null && !e.getKind().isBlank()) {
                m.put("kind", e.getKind());
            }
            if (e.getAttributes() != null && !e.getAttributes().map().isEmpty()) {
                m.put("attributes", e.getAttributes().map());
            }
            if (e.getStatus() != null
                    && (e.getStatus().getCode() != null || e.getStatus().getMessage() != null)) {
                Map<String, Object> status = new LinkedHashMap<>();
                if (e.getStatus().getCode() != null)
                    status.put("code", e.getStatus().getCode().name());
                if (e.getStatus().getMessage() != null)
                    status.put("message", e.getStatus().getMessage());
                m.put("status", status);
            }
            List<com.obsinity.telemetry.model.OEvent> children = e.getEvents();
            if (children != null && !children.isEmpty()) {
                m.put("events", serializeEvents(children));
            }
            out.add(m);
        }
        return out;
    }

    private static Instant instantFromNanos(Long epochNanos) {
        if (epochNanos == null || epochNanos <= 0) return null;
        long seconds = epochNanos / 1_000_000_000L;
        long nanos = epochNanos % 1_000_000_000L;
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private static String attribute(Map<String, Object> attrs, String key) {
        if (attrs == null) return null;
        Object v = attrs.get(key);
        return v == null ? null : sanitize(v.toString());
    }

    private static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        if (source == null) return null;
        Object v = source.get(key);
        if (v instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, value) -> {
                if (value != null) copy.put(String.valueOf(k), value);
            });
            return copy;
        }
        return null;
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return (trimmed.isEmpty() || trimmed.equals(DUMMY_SERVICE_ID)) ? null : trimmed;
    }

    private static Long toUnixNanos(Instant instant) {
        if (instant == null) return null;
        long seconds = instant.getEpochSecond();
        int nanos = instant.getNano();
        return seconds * 1_000_000_000L + nanos;
    }

    @SuppressWarnings("unchecked")
    private static void mergeNestedMap(Object source, Map<String, Object> target) {
        if (!(source instanceof Map<?, ?> src)) return;
        src.forEach((k, v) -> {
            if (k != null && v != null) target.put(String.valueOf(k), v);
        });
    }
}

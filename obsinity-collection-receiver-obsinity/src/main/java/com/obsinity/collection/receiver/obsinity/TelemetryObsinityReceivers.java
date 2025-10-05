package com.obsinity.collection.receiver.obsinity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.obsinity.client.transport.EventSender;
import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.telemetry.model.OResource;
import com.obsinity.telemetry.model.TelemetryEvent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventReceiver
public class TelemetryObsinityReceivers {
    private static final Logger log = LoggerFactory.getLogger(TelemetryObsinityReceivers.class);
    private static final String SERVICE_PROP = "obsinity.collection.service";
    private static final String SERVICE_ENV = "OBSINITY_SERVICE";
    private static final String DUMMY_SERVICE_ID = "**DUMMY-SERVICE-ID**";

    private final EventSender sender;
    private final ObjectMapper json;

    public TelemetryObsinityReceivers(EventSender sender) {
        this.sender = sender;
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @OnFlowStarted
    public void onStarted(TelemetryEvent event) throws IOException {
        send(event);
    }

    @OnFlowCompleted
    public void onCompleted(TelemetryEvent event) throws IOException {
        send(event);
    }

    @OnFlowFailure
    public void onFailed(TelemetryEvent event) throws IOException {
        send(event);
    }

    private void send(TelemetryEvent event) throws IOException {
        byte[] body = json.writeValueAsBytes(toUnifiedPublishBody(event));
        sender.send(body);
    }

    static Map<String, Object> toUnifiedPublishBody(TelemetryEvent event) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("occurredAt", event.timestamp());

        Map<String, Object> eventObj = new LinkedHashMap<>();
        eventObj.put("name", event.name());
        if (event.kind() != null) eventObj.put("kind", event.kind().name());
        root.put("event", eventObj);

        if (event.traceId() != null || event.spanId() != null || event.parentSpanId() != null) {
            Map<String, Object> trace = new LinkedHashMap<>();
            if (event.traceId() != null) trace.put("traceId", event.traceId());
            if (event.spanId() != null) trace.put("spanId", event.spanId());
            if (event.parentSpanId() != null) trace.put("parentSpanId", event.parentSpanId());
            root.put("trace", trace);
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

        Map<String, Object> attrs = new LinkedHashMap<>(
                event.attributes() == null ? Map.of() : event.attributes().map());
        root.put("attributes", attrs);

        Map<String, Object> resource = new LinkedHashMap<>();
        OResource r = event.resource();
        if (r != null) {
            if (r.attributes() != null
                    && r.attributes().map() != null
                    && !r.attributes().map().isEmpty()) {
                resource.put("attributes", r.attributes().map());
            }
        }

        String serviceId = resolveServiceId(event);
        if (serviceId != null) {
            Map<String, Object> service = new LinkedHashMap<>();
            service.put("name", serviceId);

            String namespace = extractAttribute(event, "service.namespace");
            if (namespace != null) service.put("namespace", namespace);

            String version = extractAttribute(event, "service.version");
            if (version != null) service.put("version", version);

            String instanceId = extractAttribute(event, "service.instance.id");
            if (instanceId != null) {
                Map<String, Object> instance = new LinkedHashMap<>();
                instance.put("id", instanceId);
                service.put("instance", instance);
            }

            resource.put("service", service);
        } else {
            log.warn(
                    "Missing service identifier for telemetry event '{}'; set system property '{}' or env '{}'",
                    event.name(),
                    SERVICE_PROP,
                    SERVICE_ENV);
        }

        if (!event.eventContext().isEmpty()) resource.put("context", event.eventContext());
        root.put("resource", resource);
        return root;
    }

    private static String resolveServiceId(TelemetryEvent event) {
        if (event == null) return null;

        String fromEvent = sanitize(event.effectiveServiceId());
        if (fromEvent != null) return fromEvent;

        String fromAttributes = extractAttribute(event, "service.name");
        if (fromAttributes != null) return fromAttributes;

        String sys = sanitize(System.getProperty(SERVICE_PROP));
        if (sys != null) return sys;

        String env = sanitize(System.getenv(SERVICE_ENV));
        if (env != null) return env;

        return null;
    }

    private static String extractAttribute(TelemetryEvent event, String key) {
        if (event == null || event.attributes() == null || event.attributes().map() == null) return null;
        Object raw = event.attributes().map().get(key);
        return raw == null ? null : sanitize(String.valueOf(raw));
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.equals(DUMMY_SERVICE_ID)) return null;
        return trimmed;
    }
}

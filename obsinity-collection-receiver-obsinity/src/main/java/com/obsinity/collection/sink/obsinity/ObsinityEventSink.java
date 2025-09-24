package com.obsinity.collection.sink.obsinity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.obsinity.client.transport.EventSender;
import com.obsinity.collection.core.model.OEvent;
import com.obsinity.collection.core.receivers.EventHandler;
import java.io.IOException;

public final class ObsinityEventSink implements EventHandler {
    private final EventSender sender;
    private final ObjectMapper json;

    public ObsinityEventSink(EventSender sender) {
        this.sender = sender;
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void handle(OEvent event) throws IOException {
        if (event == null) return;
        byte[] body = json.writeValueAsBytes(ObsinityPayloads.toUnifiedPublishBody(event));
        sender.send(body);
    }

    public void close() throws IOException {
        sender.close();
    }
}

// ---------- helpers ----------

final class ObsinityPayloads {
    static String serviceKey() {
        String sys = System.getProperty("obsinity.collection.service");
        if (sys != null && !sys.isBlank()) return sys;
        String env = System.getenv("OBSINITY_SERVICE");
        if (env != null && !env.isBlank()) return env;
        return "reference-client"; // sensible default for demos
    }

    static java.util.Map<String, Object> toUnifiedPublishBody(OEvent e) {
        String name = e.name();
        String suffixStatus = null;
        if (name != null) {
            if (name.endsWith(":started")) {
                suffixStatus = "STARTED";
                name = name.substring(0, name.length() - 8);
            } else if (name.endsWith(":completed")) {
                suffixStatus = "COMPLETED";
                name = name.substring(0, name.length() - 10);
            } else if (name.endsWith(":failed")) {
                suffixStatus = "FAILED";
                name = name.substring(0, name.length() - 7);
            }
        }

        java.util.Map<String, Object> attrs =
                new java.util.LinkedHashMap<>(e.attributes() == null ? java.util.Map.of() : e.attributes());

        java.util.Map<String, Object> eventObj = new java.util.LinkedHashMap<>();
        eventObj.put("name", name);
        if (e.event() != null) {
            if (e.event().kind != null) eventObj.put("kind", e.event().kind);
            if (e.event().domain != null) eventObj.put("domain", e.event().domain);
        }

        java.util.Map<String, Object> resource = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> svc = new java.util.LinkedHashMap<>();
        svc.put("name", serviceKey());
        if (e.resource() != null && e.resource().service != null) {
            if (e.resource().service.namespace != null) svc.put("namespace", e.resource().service.namespace);
            if (e.resource().service.version != null) svc.put("version", e.resource().service.version);
            if (e.resource().service.instance != null && e.resource().service.instance.id != null)
                svc.put("instance", java.util.Map.of("id", e.resource().service.instance.id));
        }
        resource.put("service", svc);
        if (e.resource() != null) {
            if (e.resource().host != null && e.resource().host.name != null)
                resource.put("host", java.util.Map.of("name", e.resource().host.name));
            if (e.resource().telemetry != null && e.resource().telemetry.sdk != null) {
                var sdk = new java.util.LinkedHashMap<String, Object>();
                if (e.resource().telemetry.sdk.name != null) sdk.put("name", e.resource().telemetry.sdk.name);
                if (e.resource().telemetry.sdk.version != null) sdk.put("version", e.resource().telemetry.sdk.version);
                resource.put("telemetry", java.util.Map.of("sdk", sdk));
            }
            if (e.resource().cloud != null) {
                var cloud = new java.util.LinkedHashMap<String, Object>();
                if (e.resource().cloud.provider != null) cloud.put("provider", e.resource().cloud.provider);
                if (e.resource().cloud.region != null) cloud.put("region", e.resource().cloud.region);
                if (!cloud.isEmpty()) resource.put("cloud", cloud);
            }
            if (e.resource().context != null && !e.resource().context.isEmpty())
                resource.put("context", e.resource().context);
        }

        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        if (e.time() != null) {
            var time = new java.util.LinkedHashMap<String, Object>();
            if (e.time().startUnixNano != null) time.put("startUnixNano", e.time().startUnixNano);
            if (e.time().endUnixNano != null) time.put("endUnixNano", e.time().endUnixNano);
            if (e.time().startedAt != null) time.put("startedAt", e.time().startedAt);
            if (e.time().endedAt != null) time.put("endedAt", e.time().endedAt);
            if (!time.isEmpty()) root.put("time", time);
        }
        root.put("occurredAt", e.occurredAt());
        root.put("event", eventObj);
        root.put("resource", resource);
        root.put("attributes", attrs);

        if (e.trace() != null) {
            var trace = new java.util.LinkedHashMap<String, Object>();
            if (e.trace().traceId != null) trace.put("traceId", e.trace().traceId);
            if (e.trace().spanId != null) trace.put("spanId", e.trace().spanId);
            if (e.trace().parentSpanId != null) trace.put("parentSpanId", e.trace().parentSpanId);
            if (e.trace().state != null) trace.put("state", e.trace().state);
            if (!trace.isEmpty()) root.put("trace", trace);
        }

        if (e.status() != null) {
            var status = new java.util.LinkedHashMap<String, Object>();
            if (e.status().code != null) status.put("code", e.status().code);
            if (e.status().message != null) status.put("message", e.status().message);
            if (!status.isEmpty()) root.put("status", status);
        } else if (suffixStatus != null) {
            root.put("status", java.util.Map.of("code", suffixStatus));
        }
        return root;
    }
}

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

@EventReceiver
public class TelemetryObsinityReceivers {
    private final EventSender sender;
    private final ObjectMapper json;

    public TelemetryObsinityReceivers(EventSender sender) {
        this.sender = sender;
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @OnFlowStarted
    public void onStarted(TelemetryEvent h) throws IOException {
        send(h);
    }

    @OnFlowCompleted
    public void onCompleted(TelemetryEvent h) throws IOException {
        send(h);
    }

    @OnFlowFailure
    public void onFailed(TelemetryEvent h) throws IOException {
        send(h);
    }

    private void send(TelemetryHolder h) throws IOException {
        byte[] body = json.writeValueAsBytes(toUnifiedPublishBody(h));
        sender.send(body);
    }

    static Map<String, Object> toUnifiedPublishBody(TelemetryEvent h) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("occurredAt", h.timestamp());

        Map<String, Object> eventObj = new LinkedHashMap<>();
        eventObj.put("name", h.name());
        if (h.kind() != null) eventObj.put("kind", h.kind().name());
        root.put("event", eventObj);

        if (h.traceId() != null || h.spanId() != null || h.parentSpanId() != null) {
            Map<String, Object> trace = new LinkedHashMap<>();
            if (h.traceId() != null) trace.put("traceId", h.traceId());
            if (h.spanId() != null) trace.put("spanId", h.spanId());
            if (h.parentSpanId() != null) trace.put("parentSpanId", h.parentSpanId());
            root.put("trace", trace);
        }

        if (h.status() != null && (h.status().getCode() != null || h.status().getMessage() != null)) {
            Map<String, Object> status = new LinkedHashMap<>();
            if (h.status().getCode() != null)
                status.put("code", String.valueOf(h.status().getCode()));
            if (h.status().getMessage() != null)
                status.put("message", h.status().getMessage());
            root.put("status", status);
        }

        Map<String, Object> attrs = new LinkedHashMap<>(
                h.attributes() == null ? Map.of() : h.attributes().map());
        root.put("attributes", attrs);

        Map<String, Object> resource = new LinkedHashMap<>();
        OResource r = h.resource();
        if (r != null) {
            if (r.attributes() != null
                    && r.attributes().map() != null
                    && !r.attributes().map().isEmpty()) {
                resource.put("attributes", r.attributes().map());
            }
        }
        if (!h.eventContext().isEmpty()) resource.put("context", h.eventContext());
        root.put("resource", resource);
        return root;
    }
}

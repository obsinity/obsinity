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
        if (!event.eventContext().isEmpty()) resource.put("context", event.eventContext());
        root.put("resource", resource);
        return root;
    }
}

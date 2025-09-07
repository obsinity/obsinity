package com.obsinity.controller.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.api.dto.PublishEventRequest;
import com.obsinity.service.core.spi.EventIngestService;
import com.obsinity.telemetry.model.EventEnvelope;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/events/{serviceId}/{eventType}")
public class PublishController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final EventIngestService ingest;
    private final ObjectMapper mapper;

    public PublishController(EventIngestService ingest, ObjectMapper mapper) {
        this.ingest = ingest;
        this.mapper = mapper;
    }

    @PostMapping("/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> publishOne(
            @PathVariable String serviceId,
            @PathVariable String eventType,
            @Valid @RequestBody PublishEventRequest body) {
        EventEnvelope env = toEnvelope(serviceId, eventType, body);
        int stored = ingest.ingestOne(env);
        return Map.of("status", stored == 1 ? "stored" : "duplicate", "eventId", env.getEventId());
    }

    @PostMapping("/publish/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> publishBatch(
            @PathVariable String serviceId,
            @PathVariable String eventType,
            @Valid @RequestBody List<PublishEventRequest> bodies) {
        List<EventEnvelope> envelopes =
                bodies.stream().map(b -> toEnvelope(serviceId, eventType, b)).toList();

        int stored = ingest.ingestBatch(envelopes);
        int total = envelopes.size();
        int duplicates = Math.max(0, total - stored);

        return Map.of("stored", stored, "duplicates", duplicates);
    }

    // ---- Mapping (DTO → Envelope) -------------------------------------------

    private EventEnvelope toEnvelope(String serviceId, String eventType, PublishEventRequest b) {

        EventEnvelope.Status status = null;
        if (b.getStatus() != null) {
            status = new EventEnvelope.Status(
                    b.getStatus().getCode(), b.getStatus().getMessage());
        }

        List<EventEnvelope.OtelEvent> events = null;
        if (b.getEvents() != null && !b.getEvents().isEmpty()) {
            events = b.getEvents().stream()
                    .map(e -> new EventEnvelope.OtelEvent(e.getName(), e.getTimestamp(), toMap(e.getAttributes())))
                    .toList();
        }

        List<EventEnvelope.OtelLink> links = null;
        if (b.getLinks() != null && !b.getLinks().isEmpty()) {
            links = b.getLinks().stream()
                    .map(l -> new EventEnvelope.OtelLink(l.getTraceId(), l.getSpanId(), toMap(l.getAttributes())))
                    .toList();
        }

        String traceId = b.getTrace() != null ? b.getTrace().getTraceId() : null;
        String spanId = b.getTrace() != null ? b.getTrace().getSpanId() : null;
        String parentSpanId = b.getTrace() != null ? b.getTrace().getParentSpanId() : null;

        return EventEnvelope.builder()
                .serviceId(serviceId)
                .eventType(eventType)
                .eventId(b.getEventId())
                .timestamp(b.getTimestamp())
                .endTimestamp(b.getEndTimestamp())
                .ingestedAt(Instant.now())
                .name(b.getName())
                .kind(b.getKind())
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .status(status)
                .resourceAttributes(
                        b.getResource() != null ? toMap(b.getResource().getAttributes()) : null)
                .attributes(toMap(b.getAttributes()))
                .events(events)
                .links(links)
                .correlationId(b.getCorrelationId())
                .synthetic(b.getSynthetic())
                .build();
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        return mapper.convertValue(node, MAP_TYPE);
    }
}

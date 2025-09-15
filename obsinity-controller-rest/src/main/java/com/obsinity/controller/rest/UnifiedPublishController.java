package com.obsinity.controller.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Body-routed publish endpoints. Service and event type are provided in the body:
 *   - resource.service.name -> serviceId
 *   - event.name            -> eventType
 */
@RestController
@RequestMapping("/events")
public class UnifiedPublishController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final EventIngestService ingest;
    private final ObjectMapper mapper;

    public UnifiedPublishController(EventIngestService ingest, ObjectMapper mapper) {
        this.ingest = ingest;
        this.mapper = mapper;
    }

    @PostMapping("/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> publishOne(@Valid @RequestBody JsonNode body) {
        EventEnvelope env = toEnvelopeFromRaw(body);
        int stored = ingest.ingestOne(env);
        return Map.of("status", stored == 1 ? "stored" : "duplicate", "eventId", env.getEventId());
    }

    @PostMapping("/publish/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> publishBatch(@Valid @RequestBody List<JsonNode> bodies) {
        List<EventEnvelope> envs = bodies.stream().map(this::toEnvelopeFromRaw).toList();
        int stored = ingest.ingestBatch(envs);
        int duplicates = Math.max(0, envs.size() - stored);
        return Map.of("stored", stored, "duplicates", duplicates);
    }

    // ---- Mapping helpers ----------------------------------------------------

    private EventEnvelope toEnvelopeFromRaw(JsonNode root) {
        Objects.requireNonNull(root, "event body is required");

        String serviceId = stringAt(root, "resource", "service", "name");
        String eventType = stringAt(root, "event", "name");
        if (serviceId == null || eventType == null) {
            throw new IllegalArgumentException("resource.service.name and event.name are required");
        }

        String eventId = coalesce(stringAt(root, "eventId"), UUID.randomUUID().toString());

        Instant occurredAt = parseInstant(coalesce(stringAt(root, "occurred_at"), stringAt(root, "timestamp")));
        Instant receivedAt = parseInstant(orNull(stringAt(root, "received_at")));
        if (receivedAt == null) receivedAt = Instant.now();

        String name = stringAt(root, "event", "name");
        String kind = stringAt(root, "event", "kind");

        String corr = stringAt(root, "trace", "correlation_id");
        String traceId = stringAt(root, "trace", "trace_id");
        String spanId = stringAt(root, "trace", "span_id");

        Map<String, Object> resource = toMap(root.path("resource"));
        Map<String, Object> attributes = toMap(root.path("attributes"));

        return EventEnvelope.builder()
                .serviceId(serviceId)
                .eventType(eventType)
                .eventId(eventId)
                .timestamp(occurredAt)
                .ingestedAt(receivedAt)
                .name(name)
                .kind(kind)
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(null)
                .status(null)
                .resourceAttributes(resource)
                .attributes(attributes)
                .events(null)
                .links(null)
                .correlationId(corr)
                .synthetic(null)
                .build();
    }

    private String stringAt(JsonNode n, String... path) {
        JsonNode cur = n;
        for (String p : path) {
            if (cur == null) return null;
            cur = cur.path(p);
        }
        if (cur == null || cur.isMissingNode() || cur.isNull()) return null;
        String s = cur.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private String orNull(String a) {
        return (a == null || a.isBlank()) ? null : a;
    }

    private Instant parseInstant(String iso) {
        if (iso == null) return null;
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.parse(iso); // try plain instant
        }
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return Map.of();
        return mapper.convertValue(node, MAP_TYPE);
    }
}

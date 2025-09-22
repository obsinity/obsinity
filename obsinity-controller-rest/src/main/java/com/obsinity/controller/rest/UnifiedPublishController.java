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

        // Require camelCase inputs (allow 'timestamp' as fallback for occurredAt)
        Instant occurredAt = parseInstantOrNanos(root, "time", "startedAt", "startUnixNano");
        if (occurredAt == null)
            occurredAt = parseInstant(coalesce(stringAt(root, "occurredAt"), stringAt(root, "timestamp")));
        Instant endAt = parseInstantOrNanos(root, "time", "endedAt", "endUnixNano");
        Instant receivedAt = parseInstant(orNull(stringAt(root, "receivedAt")));
        if (receivedAt == null) receivedAt = Instant.now();

        String name = stringAt(root, "event", "name");
        String kind = stringAt(root, "event", "kind");

        String corr = stringAt(root, "trace", "correlationId");
        String traceId = stringAt(root, "trace", "traceId");
        String spanId = stringAt(root, "trace", "spanId");

        Map<String, Object> resource = toMap(root.path("resource"));
        Map<String, Object> attributes = toMap(root.path("attributes"));

        EventEnvelope.Builder b = EventEnvelope.builder()
                .serviceId(serviceId)
                .eventType(eventType)
                .eventId(eventId)
                .timestamp(occurredAt)
                .endTimestamp(endAt)
                .ingestedAt(receivedAt)
                .name(name)
                .kind(kind)
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(null)
                .resourceAttributes(resource)
                .attributes(attributes)
                .events(null)
                .links(null)
                .correlationId(corr)
                .synthetic(null);

        // Optional status mapping { code, message }
        Map<String, Object> status = toMap(root.path("status"));
        if (!status.isEmpty()) {
            String sc = String.valueOf(status.getOrDefault("code", "")).trim();
            String sm = String.valueOf(status.getOrDefault("message", "")).trim();
            if (!sc.isEmpty() || !sm.isEmpty())
                b.status(new EventEnvelope.Status(sc.isEmpty() ? null : sc, sm.isEmpty() ? null : sm));
        }

        // Optional events[] mapping
        var eventsNode = root.path("events");
        if (eventsNode.isArray() && eventsNode.size() > 0) {
            java.util.List<EventEnvelope.OtelEvent> evts = new java.util.ArrayList<>(eventsNode.size());
            for (var n : eventsNode) {
                String ename = stringAt(n, "name");
                Instant ets = parseInstantOrNanos(n, null, "timestamp", "timeUnixNano");
                Map<String, Object> eattrs = toMap(n.path("attributes"));
                evts.add(new EventEnvelope.OtelEvent(ename, ets, eattrs));
            }
            b.events(evts);
        }

        // Optional links[] mapping
        var linksNode = root.path("links");
        if (linksNode.isArray() && linksNode.size() > 0) {
            java.util.List<EventEnvelope.OtelLink> lnks = new java.util.ArrayList<>(linksNode.size());
            for (var n : linksNode) {
                String ltr = stringAt(n, "traceId");
                String lsp = stringAt(n, "spanId");
                Map<String, Object> lattrs = toMap(n.path("attributes"));
                lnks.add(new EventEnvelope.OtelLink(ltr, lsp, lattrs));
            }
            b.links(lnks);
        }

        return b.build();
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

    private Instant parseInstantOrNanos(JsonNode root, String timeKey, String isoField, String nanosField) {
        try {
            JsonNode node = (timeKey == null) ? root : root.path(timeKey);
            // Prefer ISO field if present
            String iso = stringAt(node, isoField);
            if (iso != null) return parseInstant(iso);
            // Fall back to Unix nanos
            JsonNode nanos = node.path(nanosField);
            if (nanos != null && nanos.isNumber()) {
                long ns = nanos.asLong();
                long secs = ns / 1_000_000_000L;
                long rem = ns % 1_000_000_000L;
                return Instant.ofEpochSecond(secs, rem);
            }
        } catch (Exception ignore) {
            // ignore and return null
        }
        return null;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return Map.of();
        return mapper.convertValue(node, MAP_TYPE);
    }
}

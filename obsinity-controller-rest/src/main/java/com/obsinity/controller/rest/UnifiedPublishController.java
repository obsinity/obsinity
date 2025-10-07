package com.obsinity.controller.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(UnifiedPublishController.class);
    private final EventIngestService ingest;
    private final ObjectMapper mapper;

    public UnifiedPublishController(EventIngestService ingest, ObjectMapper mapper) {
        this.ingest = ingest;
        this.mapper = mapper;
    }

    @PostMapping("/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> publishOne(@Valid @RequestBody JsonNode body) {
        try {
            EventEnvelope env = toEnvelopeFromRaw(body);
            int stored = ingest.ingestOne(env);
            return Map.of("status", stored == 1 ? "stored" : "duplicate", "eventId", env.getEventId());
        } catch (RuntimeException ex) {
            logRejectedPayload(body, ex);
            throw ex;
        }
    }

    @PostMapping("/publish/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> publishBatch(@Valid @RequestBody List<JsonNode> bodies) {
        try {
            List<EventEnvelope> envs =
                    bodies.stream().map(this::toEnvelopeFromRaw).toList();
            int stored = ingest.ingestBatch(envs);
            int duplicates = Math.max(0, envs.size() - stored);
            return Map.of("stored", stored, "duplicates", duplicates);
        } catch (RuntimeException ex) {
            logRejectedPayload(bodies, ex);
            throw ex;
        }
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

        // Require camelCase inputs (allow legacy 'occurredAt' or 'timestamp' as fallback)
        Instant startedAt = parseInstantOrNanos(root, "time", "startedAt", "startUnixNano");
        if (startedAt == null) {
            String legacyStart = coalesce(stringAt(root, "occurredAt"), stringAt(root, "timestamp"));
            startedAt = parseInstant(coalesce(stringAt(root, "startedAt"), legacyStart));
        }
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
                .timestamp(startedAt)
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
            b.events(parseOtelEvents(eventsNode));
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
            if (iso == null && timeKey != null) {
                iso = stringAt(root, isoField); // legacy payloads without time{}
            }
            if (iso != null) return parseInstant(iso);
            // Fall back to Unix nanos
            JsonNode nanos = node.path(nanosField);
            if ((nanos == null || !nanos.isNumber()) && timeKey != null) {
                nanos = root.path(nanosField);
            }
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

    private void logRejectedPayload(Object payload, RuntimeException cause) {
        if (!log.isInfoEnabled()) return;
        try {
            String rendered = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            String reason = (cause.getMessage() == null || cause.getMessage().isBlank())
                    ? cause.getClass().getSimpleName()
                    : cause.getMessage();
            log.info("Rejected publish payload due to {}:\n{}", reason, rendered);
        } catch (JsonProcessingException e) {
            log.info(
                    "Rejected publish payload; failed to render JSON ({}: {})",
                    e.getClass().getSimpleName(),
                    e.getOriginalMessage());
        }
    }

    private java.util.List<EventEnvelope.OtelEvent> parseOtelEvents(JsonNode arrayNode) {
        java.util.List<EventEnvelope.OtelEvent> evts = new java.util.ArrayList<>(arrayNode.size());
        for (JsonNode n : arrayNode) {
            String ename = stringAt(n, "name");
            Instant started = parseInstantOrNanos(n, "time", "startedAt", "startUnixNano");
            Instant ended = parseInstantOrNanos(n, "time", "endedAt", "endUnixNano");
            Long startNanos = numberValue(n, "time", "startUnixNano");
            Long endNanos = numberValue(n, "time", "endUnixNano");
            String kind = stringAt(n, "kind");
            Map<String, Object> attrs = toMap(n.path("attributes"));
            java.util.List<EventEnvelope.OtelEvent> children = java.util.List.of();
            JsonNode childrenNode = n.path("events");
            if (childrenNode.isArray() && childrenNode.size() > 0) {
                children = parseOtelEvents(childrenNode);
            }
            EventEnvelope.Status status = parseStatus(n.path("status"));
            evts.add(new EventEnvelope.OtelEvent(
                    ename, started, ended, startNanos, endNanos, kind, attrs, children, status));
        }
        return evts;
    }

    private Long numberValue(JsonNode node, String timeKey, String field) {
        JsonNode context = (timeKey == null) ? node : node.path(timeKey);
        JsonNode candidate = context.path(field);
        if ((candidate == null || !candidate.isNumber()) && timeKey != null) {
            candidate = node.path(field);
        }
        return (candidate != null && candidate.isNumber()) ? candidate.longValue() : null;
    }

    private EventEnvelope.Status parseStatus(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String code = stringAt(node, "code");
        String message = stringAt(node, "message");
        if (code == null && message == null) return null;
        return new EventEnvelope.Status(code, message);
    }
}

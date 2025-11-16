package com.obsinity.service.core.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.model.EventEnvelope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared mapper that converts canonical JSON payloads into {@link EventEnvelope}s.
 */
@Component
public class EventEnvelopeMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;

    public EventEnvelopeMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EventEnvelope fromJson(JsonNode root) {
        Objects.requireNonNull(root, "event body is required");

        String serviceId = stringAt(root, "resource", "service", "name");
        String eventType = stringAt(root, "event", "name");
        if (serviceId == null || eventType == null) {
            throw new IllegalArgumentException("resource.service.name and event.name are required");
        }

        String eventId = coalesce(stringAt(root, "eventId"), UUID.randomUUID().toString());

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

        Map<String, Object> resource = expandDottedKeys(toMap(root.path("resource")));
        Map<String, Object> attributes = expandDottedKeys(toMap(root.path("attributes")));

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

        Map<String, Object> status = toMap(root.path("status"));
        if (!status.isEmpty()) {
            String sc = String.valueOf(status.getOrDefault("code", "")).trim();
            String sm = String.valueOf(status.getOrDefault("message", "")).trim();
            if (!sc.isEmpty() || !sm.isEmpty())
                b.status(new EventEnvelope.Status(sc.isEmpty() ? null : sc, sm.isEmpty() ? null : sm));
        }

        JsonNode eventsNode = root.path("events");
        if (eventsNode.isArray() && eventsNode.size() > 0) {
            b.events(parseOtelEvents(eventsNode));
        }

        JsonNode linksNode = root.path("links");
        if (linksNode.isArray() && linksNode.size() > 0) {
            List<EventEnvelope.OtelLink> links = new ArrayList<>(linksNode.size());
            for (JsonNode n : linksNode) {
                String ltr = stringAt(n, "traceId");
                String lsp = stringAt(n, "spanId");
                Map<String, Object> lattrs = expandDottedKeys(toMap(n.path("attributes")));
                links.add(new EventEnvelope.OtelLink(ltr, lsp, lattrs));
            }
            b.links(links);
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
            return Instant.parse(iso);
        }
    }

    private Instant parseInstantOrNanos(JsonNode root, String timeKey, String isoField, String nanosField) {
        try {
            JsonNode node = (timeKey == null) ? root : root.path(timeKey);
            String iso = stringAt(node, isoField);
            if (iso == null && timeKey != null) {
                iso = stringAt(root, isoField);
            }
            if (iso != null) return parseInstant(iso);

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
            // ignore
        }
        return null;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return Map.of();
        return mapper.convertValue(node, MAP_TYPE);
    }

    private Map<String, Object> expandDottedKeys(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> target = new LinkedHashMap<>();
        source.forEach((k, v) -> mergeAttribute(target, k, v));
        return target;
    }

    @SuppressWarnings("unchecked")
    private void mergeAttribute(Map<String, Object> target, String rawKey, Object value) {
        if (rawKey == null || rawKey.isBlank()) return;
        String key = rawKey.trim();

        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> expanded = new LinkedHashMap<>();
            mapValue.forEach((k, v) -> mergeAttribute(expanded, k != null ? k.toString() : null, v));
            value = expanded;
        }

        if (!key.contains(".")) {
            target.put(key, value);
            return;
        }

        String[] segments = key.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            Object next = current.get(segment);
            if (!(next instanceof Map<?, ?> nextMap)) {
                Map<String, Object> fresh = new LinkedHashMap<>();
                current.put(segment, fresh);
                current = fresh;
            } else {
                Map<String, Object> mutable = new LinkedHashMap<>();
                nextMap.forEach((k, v) -> mutable.put(k != null ? k.toString() : null, v));
                current.put(segment, mutable);
                current = mutable;
            }
        }
        current.put(segments[segments.length - 1], value);
    }

    private List<EventEnvelope.OtelEvent> parseOtelEvents(JsonNode arrayNode) {
        List<EventEnvelope.OtelEvent> evts = new ArrayList<>(arrayNode.size());
        for (JsonNode n : arrayNode) {
            String ename = stringAt(n, "name");
            Instant started = parseInstantOrNanos(n, "time", "startedAt", "startUnixNano");
            Instant ended = parseInstantOrNanos(n, "time", "endedAt", "endUnixNano");
            Long startNanos = numberValue(n, "time", "startUnixNano");
            Long endNanos = numberValue(n, "time", "endUnixNano");
            String kind = stringAt(n, "kind");
            Map<String, Object> attrs = expandDottedKeys(toMap(n.path("attributes")));
            List<EventEnvelope.OtelEvent> children = List.of();
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

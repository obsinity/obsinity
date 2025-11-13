package com.obsinity.service.core.impl;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.EventTypeConfig;
import com.obsinity.service.core.counter.CounterIngestService;
import com.obsinity.service.core.histogram.HistogramIngestService;
import com.obsinity.service.core.index.AttributeIndexingService;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import com.obsinity.service.core.unconfigured.UnconfiguredEventQueue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcEventIngestService implements EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(JdbcEventIngestService.class);

    private static final String INSERT_SQL =
            """
        insert into events_raw(
              event_id, parent_event_id, event_type_id, started_at, completed_at, duration_nanos, received_at,
              event_type, kind, service_partition_key, trace_id, span_id, parent_span_id, correlation_id, status, attributes
        ) values (
              :event_id, :parent_event_id, :event_type_id, :started_at, :completed_at, :duration_nanos, :received_at,
              :event_type, :kind, :service_partition_key, :trace_id, :span_id, :parent_span_id, :correlation_id, :status, cast(:attributes as jsonb)
        )
        on conflict (service_partition_key, started_at, event_id) do nothing
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AttributeIndexingService attributeIndexingService;
    private final ConfigLookup configLookup;
    private final UnconfiguredEventQueue unconfiguredEventQueue;
    private final CounterIngestService counterIngestService;
    private final HistogramIngestService histogramIngestService;

    @Value("${obsinity.counters.enabled:true}")
    private boolean countersEnabled;

    @Value("${obsinity.histograms.enabled:true}")
    private boolean histogramsEnabled;

    // tiny in-memory cache to avoid re-hashing/upserting each time
    private final Map<String, String> servicePartitionKeyCache = new ConcurrentHashMap<>();

    public JdbcEventIngestService(
            NamedParameterJdbcTemplate jdbc,
            AttributeIndexingService attributeIndexingService,
            ConfigLookup configLookup,
            UnconfiguredEventQueue unconfiguredEventQueue,
            CounterIngestService counterIngestService,
            HistogramIngestService histogramIngestService) {
        this.jdbc = jdbc;
        this.attributeIndexingService = attributeIndexingService;
        this.configLookup = configLookup;
        this.unconfiguredEventQueue = unconfiguredEventQueue;
        this.counterIngestService = counterIngestService;
        this.histogramIngestService = histogramIngestService;
    }

    @Override
    public int ingestOne(EventEnvelope e) {
        if (log.isDebugEnabled()) {
            log.debug("Ingesting event envelope:\n{}", JsonUtil.toPrettyJson(e));
        }
        final UUID eventId = UUID.fromString(e.getEventId());
        final OffsetDateTime startedAt = OffsetDateTime.ofInstant(e.getTimestamp(), ZoneOffset.UTC);
        final OffsetDateTime receivedAt = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        final String eventType = e.getName();

        final String serviceKey = e.getServiceId();
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalArgumentException("EventEnvelope.serviceId is required");
        }

        final String servicePartitionKey =
                servicePartitionKeyCache.computeIfAbsent(serviceKey, this::ensureServiceAndGetPartitionKey);
        final UUID serviceId = resolveServiceId(serviceKey);

        if (!configLookup.isServiceConfigured(serviceId)) {
            String message = "Service '" + serviceKey + "' is not configured";
            log.warn(message);
            unconfiguredEventQueue.publish(e, "UNCONFIGURED_SERVICE", message);
            return 0;
        }

        final Map<String, Object> attrs = e.getAttributes();
        String lifecycle = lifecycle(attrs);
        if ("STARTED".equalsIgnoreCase(lifecycle)) {
            return 0;
        }

        var eventConfigOpt = configLookup.get(serviceId, eventType);
        if (eventConfigOpt.isEmpty()) {
            String message = "Event type '" + eventType + "' for service '" + serviceKey + "' is not configured";
            log.warn(message);
            unconfiguredEventQueue.publish(e, "UNCONFIGURED_EVENT_TYPE", message);
            return 0;
        }
        EventTypeConfig eventConfig = eventConfigOpt.get();
        final UUID eventTypeId = eventConfig.eventId();

        final OffsetDateTime completedAt =
                e.getEndTimestamp() != null ? OffsetDateTime.ofInstant(e.getEndTimestamp(), ZoneOffset.UTC) : null;
        Long durationNanos = (e.getEndTimestamp() != null && e.getTimestamp() != null)
                ? Duration.between(e.getTimestamp(), e.getEndTimestamp()).toNanos()
                : null;
        Long attrDuration = extractDurationFromAttributes(attrs);
        if (attrDuration != null) durationNanos = attrDuration;
        final String status = resolveStatus(e, lifecycle);

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("event_id", eventId)
                .addValue("parent_event_id", null)
                .addValue("event_type_id", eventTypeId)
                .addValue("started_at", startedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("completed_at", completedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("duration_nanos", durationNanos)
                .addValue("received_at", receivedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("event_type", eventType)
                .addValue("kind", e.getKind())
                .addValue("service_partition_key", servicePartitionKey)
                .addValue("trace_id", e.getTraceId())
                .addValue("span_id", e.getSpanId())
                .addValue("parent_span_id", e.getParentSpanId())
                .addValue("correlation_id", e.getCorrelationId())
                .addValue("status", status)
                .addValue("attributes", JsonUtil.toJson(attrs));

        final int wrote = jdbc.update(INSERT_SQL, p);

        if (wrote == 1 && serviceId != null) {
            attributeIndexingService.indexEvent(new AttributeIndexingService.EventForIndex() {
                @Override
                public String servicePartitionKey() {
                    return servicePartitionKey;
                }

                @Override
                public UUID serviceId() {
                    return serviceId;
                }

                @Override
                public UUID eventTypeId() {
                    return eventTypeId;
                }

                @Override
                public String eventType() {
                    return eventType;
                }

                @Override
                public UUID eventId() {
                    return eventId;
                }

                @Override
                public OffsetDateTime startedAt() {
                    return startedAt;
                }

                @Override
                public Map<String, Object> attributes() {
                    return attrs;
                }
            });

            storeSubEvents(
                    e.getEvents(),
                    eventId,
                    eventTypeId,
                    eventType,
                    servicePartitionKey,
                    serviceId,
                    serviceKey,
                    startedAt,
                    completedAt,
                    durationNanos,
                    receivedAt,
                    e.getTraceId(),
                    e.getSpanId(),
                    e.getParentSpanId(),
                    e.getCorrelationId(),
                    e);
        }

        if (countersEnabled
                && eventConfig.counters() != null
                && !eventConfig.counters().isEmpty()) {
            try {
                counterIngestService.process(e, eventConfig);
            } catch (Exception counterEx) {
                log.error("Failed to buffer counters for event {}:{}", serviceKey, eventType, counterEx);
            }
        }

        if (histogramsEnabled
                && eventConfig.histograms() != null
                && !eventConfig.histograms().isEmpty()) {
            try {
                histogramIngestService.process(e, eventConfig);
            } catch (Exception histogramEx) {
                log.error("Failed to buffer histograms for event {}:{}", serviceKey, eventType, histogramEx);
            }
        }

        return wrote;
    }

    @Override
    public int ingestBatch(java.util.List<EventEnvelope> events) {
        int stored = 0;
        for (EventEnvelope e : events) {
            stored += ingestOne(e);
        }
        return stored;
    }

    private EventTypeConfig resolveEventConfig(UUID serviceId, String eventType, String serviceKey) {
        return configLookup
                .get(serviceId, eventType)
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown event type '" + eventType + "' for service '" + serviceKey + "'"));
    }

    private String ensureServiceAndGetPartitionKey(String serviceKey) {
        String partitionKey = partitionKeyFor(serviceKey);

        try {
            jdbc.update(
                    """
                insert into service_registry (service_key, service_partition_key, description)
                values (:service_key, :service_partition_key, :description)
                on conflict (service_key) do nothing
                """,
                    new MapSqlParameterSource()
                            .addValue("service_key", serviceKey)
                            .addValue("service_partition_key", partitionKey)
                            .addValue("description", "auto-registered"));
        } catch (DataAccessException ignore) {
        }
        return partitionKey;
    }

    private UUID resolveServiceId(String serviceKey) {
        try {
            return jdbc.queryForObject(
                    "select id from service_registry where service_key = :service_key",
                    new MapSqlParameterSource().addValue("service_key", serviceKey),
                    (rs, rowNum) -> (UUID) rs.getObject(1));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String partitionKeyFor(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.substring(0, 8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute service partition key hash", ex);
        }
    }

    private void storeSubEvents(
            List<EventEnvelope.OtelEvent> subEvents,
            UUID parentEventId,
            UUID parentEventTypeId,
            String parentEventType,
            String servicePartitionKey,
            UUID serviceId,
            String serviceKey,
            OffsetDateTime parentStartedAt,
            OffsetDateTime parentCompletedAt,
            Long parentDurationNanos,
            OffsetDateTime receivedAt,
            String traceId,
            String spanId,
            String parentSpanId,
            String correlationId,
            EventEnvelope rootEnvelope) {

        if (subEvents == null || subEvents.isEmpty()) return;

        for (EventEnvelope.OtelEvent sub : subEvents) {
            UUID subEventId = generateSubEventId(parentEventId, sub);
            Instant subStartInstant = coalesceInstant(sub.getTimestamp(), sub.getTimeUnixNano());
            OffsetDateTime subStarted = subStartInstant != null
                    ? OffsetDateTime.ofInstant(subStartInstant, ZoneOffset.UTC)
                    : parentStartedAt;
            Instant subEndInstant = coalesceInstant(sub.getEndTimestamp(), sub.getEndUnixNano());
            OffsetDateTime subCompleted =
                    subEndInstant != null ? OffsetDateTime.ofInstant(subEndInstant, ZoneOffset.UTC) : parentCompletedAt;
            Long subDurationNanos = null;
            if (subStartInstant != null && subEndInstant != null) {
                subDurationNanos =
                        Duration.between(subStartInstant, subEndInstant).toNanos();
            }
            Long attrSubDuration = extractDurationFromAttributes(sub.getAttributes());
            if (attrSubDuration != null) {
                subDurationNanos = attrSubDuration;
            }
            if (subDurationNanos == null) {
                subDurationNanos = parentDurationNanos;
            }
            if (subEndInstant == null && subDurationNanos != null && subStartInstant != null) {
                subEndInstant = subStartInstant.plusNanos(subDurationNanos);
                subCompleted = OffsetDateTime.ofInstant(subEndInstant, ZoneOffset.UTC);
            }
            String subEventType = buildSubEventType(parentEventType, sub.getName());

            UUID subEventTypeId = null;
            if (serviceId != null) {
                var subConfig = configLookup.get(serviceId, subEventType);
                if (subConfig.isPresent()) {
                    subEventTypeId = subConfig.get().eventId();
                } else {
                    String message = "Nested event type '" + subEventType + "' for service '" + serviceKey
                            + "' is not configured";
                    log.warn(message);
                    unconfiguredEventQueue.publish(
                            buildUnconfiguredEventEnvelope(
                                    rootEnvelope,
                                    serviceKey,
                                    subEventType,
                                    subEventId,
                                    subStartInstant,
                                    subEndInstant,
                                    receivedAt,
                                    sub,
                                    traceId,
                                    spanId,
                                    parentSpanId,
                                    correlationId),
                            "UNCONFIGURED_NESTED_EVENT_TYPE",
                            message);
                    continue;
                }
            } else {
                String message = "Service configuration not found for nested event '" + subEventType + "'";
                log.warn(message);
                unconfiguredEventQueue.publish(
                        buildUnconfiguredEventEnvelope(
                                rootEnvelope,
                                serviceKey,
                                subEventType,
                                subEventId,
                                subStartInstant,
                                subEndInstant,
                                receivedAt,
                                sub,
                                traceId,
                                spanId,
                                parentSpanId,
                                correlationId),
                        "UNCONFIGURED_SERVICE",
                        message);
                continue;
            }
            String subStatus = resolveSubEventStatus(sub);

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("event_id", subEventId)
                    .addValue("parent_event_id", parentEventId)
                    .addValue("event_type_id", subEventTypeId)
                    .addValue("started_at", subStarted, Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("completed_at", subCompleted, Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("duration_nanos", subDurationNanos)
                    .addValue("received_at", receivedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("event_type", subEventType)
                    .addValue("kind", sub.getKind())
                    .addValue("service_partition_key", servicePartitionKey)
                    .addValue("trace_id", traceId)
                    .addValue("span_id", spanId)
                    .addValue("parent_span_id", parentSpanId)
                    .addValue("correlation_id", correlationId)
                    .addValue("status", subStatus)
                    .addValue(
                            "attributes",
                            JsonUtil.toJson(sub.getAttributes() == null ? Map.of() : sub.getAttributes()));

            jdbc.update(INSERT_SQL, params);

            if (sub.getEvents() != null && !sub.getEvents().isEmpty()) {
                storeSubEvents(
                        sub.getEvents(),
                        subEventId,
                        subEventTypeId,
                        subEventType,
                        servicePartitionKey,
                        serviceId,
                        serviceKey,
                        subStarted != null ? subStarted : parentStartedAt,
                        subCompleted != null ? subCompleted : parentCompletedAt,
                        subDurationNanos != null ? subDurationNanos : parentDurationNanos,
                        receivedAt,
                        traceId,
                        spanId,
                        parentSpanId,
                        correlationId,
                        rootEnvelope);
            }
        }
    }

    private EventEnvelope buildUnconfiguredEventEnvelope(
            EventEnvelope rootEnvelope,
            String serviceKey,
            String eventType,
            UUID eventId,
            Instant started,
            Instant ended,
            OffsetDateTime receivedAt,
            EventEnvelope.OtelEvent event,
            String traceId,
            String spanId,
            String parentSpanId,
            String correlationId) {

        Instant startInstant = started;
        if (startInstant == null) {
            if (rootEnvelope != null && rootEnvelope.getTimestamp() != null) {
                startInstant = rootEnvelope.getTimestamp();
            } else if (receivedAt != null) {
                startInstant = receivedAt.toInstant();
            } else {
                startInstant = Instant.now();
            }
        }

        Instant ingestInstant = receivedAt != null ? receivedAt.toInstant() : Instant.now();
        Map<String, Object> attrs = event.getAttributes() == null ? Map.of() : event.getAttributes();

        EventEnvelope.Builder builder = EventEnvelope.builder()
                .serviceId(serviceKey)
                .eventType(eventType)
                .eventId(eventId.toString())
                .timestamp(startInstant)
                .ingestedAt(ingestInstant)
                .attributes(attrs)
                .resourceAttributes(rootEnvelope != null ? rootEnvelope.getResourceAttributes() : Map.of())
                .events(event.getEvents())
                .links(null)
                .name(event.getName())
                .kind(event.getKind())
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .correlationId(correlationId)
                .synthetic(rootEnvelope != null ? rootEnvelope.getSynthetic() : null);

        if (ended != null) builder.endTimestamp(ended);
        if (event.getStatus() != null) builder.status(event.getStatus());

        return builder.build();
    }

    private String resolveSubEventStatus(EventEnvelope.OtelEvent sub) {
        if (sub.getStatus() != null
                && sub.getStatus().getCode() != null
                && !sub.getStatus().getCode().isBlank()) {
            String code = sub.getStatus().getCode();
            if ("OK".equalsIgnoreCase(code)) return "SUCCESS";
            if ("ERROR".equalsIgnoreCase(code)) return "FAILURE";
            return code;
        }
        Map<String, Object> attrs = sub.getAttributes();
        if (attrs != null) {
            Object outcome = attrs.get("outcome");
            if (outcome != null) return outcome.toString();
            if (attrs.containsKey("error")) return "FAILURE";
        }
        return "UNKNOWN";
    }

    private static String resolveStatus(EventEnvelope envelope, String lifecycleAttr) {
        EventEnvelope.Status status = envelope.getStatus();
        if (status != null && status.getCode() != null && !status.getCode().isBlank()) {
            String code = status.getCode().trim().toUpperCase(Locale.ROOT);
            if (code.equals("OK")) return "SUCCESS";
            if (code.equals("ERROR")) return "FAILURE";
            return code;
        }

        if (lifecycleAttr != null) {
            String normalized = lifecycleAttr.trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("COMPLETED")) return "SUCCESS";
            if (normalized.equals("FAILED")) return "FAILURE";
        }

        Object outcome =
                envelope.getAttributes() != null ? envelope.getAttributes().get("outcome") : null;
        if (outcome != null) {
            String normalized = outcome.toString().trim().toUpperCase(Locale.ROOT);
            if (!normalized.isEmpty()) return normalized;
        }

        if (envelope.getAttributes() != null && envelope.getAttributes().containsKey("error")) return "FAILURE";
        return "UNKNOWN";
    }

    private static UUID generateSubEventId(UUID parentEventId, EventEnvelope.OtelEvent subEvent) {
        String raw = parentEventId
                + "|"
                + (subEvent.getName() == null ? "" : subEvent.getName())
                + "|"
                + (subEvent.getTimestamp() == null
                        ? ""
                        : subEvent.getTimestamp().toString());
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String buildSubEventType(String parentType, String subEventName) {
        if (subEventName == null || subEventName.isBlank()) {
            String base = parentType == null ? "event" : parentType;
            return base + ":event";
        }
        return subEventName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
    }

    private static String lifecycle(Map<String, Object> attrs) {
        if (attrs == null) return null;
        Object lifecycle = attrs.get("lifecycle");
        return lifecycle == null ? null : lifecycle.toString();
    }

    private Instant coalesceInstant(Instant instant, Long nanos) {
        if (instant != null) return instant;
        if (nanos == null) return null;
        long seconds = nanos / 1_000_000_000L;
        long fractional = nanos % 1_000_000_000L;
        return Instant.ofEpochSecond(seconds, fractional);
    }

    private Long extractDurationFromAttributes(Map<String, Object> attrs) {
        if (attrs == null) return null;
        Object raw = attrs.get("duration.nanos");
        if (raw == null) return null;
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return null;
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ex) {
                log.debug("Unable to parse duration.nanos attribute '{}'", text);
            }
        }
        return null;
    }
}

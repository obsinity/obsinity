package com.obsinity.service.core.impl;

import com.obsinity.service.core.index.AttributeIndexingService;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcEventIngestService implements EventIngestService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AttributeIndexingService attributeIndexingService;

    // tiny in-memory cache to avoid re-hashing/upserting each time
    private final Map<String, String> serviceShortCache = new ConcurrentHashMap<>();

    public JdbcEventIngestService(NamedParameterJdbcTemplate jdbc, AttributeIndexingService attributeIndexingService) {
        this.jdbc = jdbc;
        this.attributeIndexingService = attributeIndexingService;
    }

    @Override
    public int ingestOne(EventEnvelope e) {
        final UUID eventId = UUID.fromString(e.getEventId());
        final OffsetDateTime occurredAt = OffsetDateTime.ofInstant(e.getTimestamp(), ZoneOffset.UTC);
        final OffsetDateTime receivedAt = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        final String eventType = e.getName();

        // Resolve service_key from envelope (full name/key)
        final String serviceKey = e.getServiceId();
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalArgumentException("EventEnvelope.serviceId is required");
        }

        // Ensure service exists; get short key for partition routing
        final String serviceShort = serviceShortCache.computeIfAbsent(serviceKey, this::ensureServiceAndGetShortKey);

        // Also lookup the service_id (UUID) for indexing rows
        final UUID serviceId = resolveServiceId(serviceKey);

        // span-level attributes (may be null)
        final Map<String, Object> attrs = e.getAttributes();

        // Resolve event type registry id; enforce linkage
        final UUID eventTypeId = resolveEventTypeId(serviceKey, eventType);
        if (eventTypeId == null) {
            throw new IllegalStateException("Unknown event type '" + eventType + "' for service '" + serviceKey + "'");
        }

        final String sql =
                """
            insert into events_raw(
                  event_id, event_type_id, occurred_at, received_at, event_type, service_short, trace_id, span_id, correlation_id, attributes
            ) values (
                  :event_id, :event_type_id, :occurred_at, :received_at, :event_type, :service_short, :trace_id, :span_id, :correlation_id, cast(:attributes as jsonb)
            )
            on conflict (service_short, occurred_at, event_id) do nothing
            """;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("event_id", eventId)
                .addValue("event_type_id", eventTypeId)
                .addValue("occurred_at", occurredAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("received_at", receivedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("event_type", eventType)
                .addValue("service_short", serviceShort)
                .addValue("trace_id", e.getTraceId())
                .addValue("span_id", e.getSpanId())
                .addValue("correlation_id", e.getCorrelationId())
                .addValue("attributes", JsonUtil.toJson(attrs));

        final int wrote = jdbc.update(sql, p);

        // Only index if this insert created a new row (idempotent on conflict)
        if (wrote == 1 && serviceId != null) {
            attributeIndexingService.indexEvent(new AttributeIndexingService.EventForIndex() {
                @Override
                public String serviceKey() {
                    return serviceShort;
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
                public UUID eventId() {
                    return eventId;
                }

                @Override
                public OffsetDateTime occurredAt() {
                    return occurredAt;
                }

                @Override
                public Map<String, Object> attributes() {
                    return attrs;
                }
            });
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

    /** Ensure services row exists (upsert) and return its 8-char short_key. */
    private String ensureServiceAndGetShortKey(String serviceKey) {
        String shortKey = shortHash8(serviceKey);

        try {
            jdbc.update(
                    """
                insert into service_registry (service_key, short_key, description)
                values (:service_key, :short_key, :description)
                on conflict (service_key) do nothing
                """,
                    new MapSqlParameterSource()
                            .addValue("service_key", serviceKey)
                            .addValue("short_key", shortKey)
                            .addValue("description", "auto-registered"));
        } catch (DataAccessException ignore) {
            // harmless if races occur; uniqueness handles it
        }
        return shortKey;
    }

    /** Look up the service's UUID id from services by service_key. */
    private UUID resolveServiceId(String serviceKey) {
        try {
            return jdbc.queryForObject(
                    "select id from service_registry where service_key = :service_key",
                    new MapSqlParameterSource().addValue("service_key", serviceKey),
                    (rs, rowNum) -> (UUID) rs.getObject(1));
        } catch (Exception ex) {
            return null; // not fatal; indexing will be skipped
        }
    }

    private UUID resolveEventTypeId(String serviceKey, String eventType) {
        try {
            return jdbc.queryForObject(
                    """
                select er.id
                from event_registry er
                join service_registry s on s.id = er.service_id
                where s.service_key = :service_key
                  and er.event_name  = :event_type
                limit 1
                """,
                    new MapSqlParameterSource()
                            .addValue("service_key", serviceKey)
                            .addValue("event_type", eventType),
                    (rs, rowNum) -> (UUID) rs.getObject(1));
        } catch (Exception ex) {
            return null; // skip indexing if not registered yet
        }
    }

    /** 8-char lowercase hex SHA-256 of the service key (deterministic). */
    private static String shortHash8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.substring(0, 8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute service short hash", ex);
        }
    }
}

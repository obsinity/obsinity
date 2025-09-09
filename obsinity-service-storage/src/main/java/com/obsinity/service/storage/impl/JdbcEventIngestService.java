package com.obsinity.service.storage.impl;

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
    // tiny in-memory cache to avoid re-hashing/upserting each time
    private final Map<String, String> serviceShortCache = new ConcurrentHashMap<>();

    public JdbcEventIngestService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int ingestOne(EventEnvelope e) {
        final UUID eventId = UUID.fromString(e.getEventId());
        final OffsetDateTime tsZ = OffsetDateTime.ofInstant(e.getTimestamp(), ZoneOffset.UTC);
        final OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        final String type = e.getName();

        // Resolve service_key from envelope; you were using serviceId — keep that as the "full name"
        final String serviceKey = e.getServiceId();
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalArgumentException("EventEnvelope.serviceId is required");
        }
        final String serviceShort = serviceShortCache.computeIfAbsent(serviceKey, this::ensureServiceAndGetShortKey);

        // span-level attributes only (may be null)
        final Map<String, Object> attrs = e.getAttributes();

        final String sql =
                """
            insert into events_raw(
                      event_id, occurred_at, received_at, event_type, service_short, trace_id, span_id, correlation_id, attributes
            ) values (
                      :event_id, :occurred_at, :received_at, :event_type, :service_short, :trace_id, :span_id, :correlation_id, cast(:attributes as jsonb)
            )
            on conflict (service_short, occurred_at, event_id) do nothing
            """;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("event_id", eventId)
                .addValue("occurred_at", tsZ, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("received_at", now, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("event_type", type)
                .addValue("service_short", serviceShort)
                .addValue("trace_id", e.getTraceId())
                .addValue("span_id", e.getSpanId())
                .addValue("correlation_id", e.getCorrelationId())
                .addValue("attributes", JsonUtil.toJson(attrs));

        return jdbc.update(sql, p);
    }

    @Override
    public int ingestBatch(java.util.List<EventEnvelope> events) {
        int stored = 0;
        for (EventEnvelope e : events) {
            stored += ingestOne(e);
        }
        return stored;
    }

    /** Ensure services row exists and return its 8-char short_key. */
    private String ensureServiceAndGetShortKey(String serviceKey) {
        String shortKey = shortHash8(serviceKey);

        // Upsert service record (id is generated; short_key is unique)
        try {
            jdbc.update(
                    """
                insert into services (service_key, short_key, description)
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

        // Optionally you could read back DB value, but since we deterministically compute
        // the 8-char key from serviceKey, returning shortKey is fine.
        return shortKey;
    }

    /** 8-char lowercase hex SHA-256 of the service key (deterministic, matches DB rule). */
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

package com.obsinity.service.storage.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcEventIngestService implements EventIngestService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcEventIngestService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public int ingestOne(EventEnvelope e) {
        final UUID eventId = parseOrGenerateUuid(e.getEventId());
        final OffsetDateTime tsZ = OffsetDateTime.ofInstant(e.getTimestamp(), ZoneOffset.UTC);

        final Map<String, Object> attrs =
                (e.getAttributes() != null && e.getAttributes().map() != null)
                        ? e.getAttributes().map()
                        : new LinkedHashMap<>();

        final String attrsJson = toJson(attrs);

        final String sql =
                """
            insert into events_raw(
              event_id, ts, type, service_id, trace_id, span_id, correlation_id, attributes
            ) values (
              :event_id, :ts, :type, :service_id, :trace_id, :span_id, :correlation_id, cast(:attributes as jsonb)
            )
            on conflict (event_id) do nothing
            """;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("event_id", eventId)
                .addValue("ts", tsZ, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("type", e.getName())
                .addValue("service_id", e.getServiceId())
                .addValue("trace_id", e.getTraceId())
                .addValue("span_id", e.getSpanId())
                .addValue("correlation_id", e.getCorrelationId())
                .addValue("attributes", attrsJson);

        return jdbc.update(sql, p);
    }

    @Override
    public int ingestBatch(List<EventEnvelope> events) {
        int stored = 0;
        for (EventEnvelope e : events) stored += ingestOne(e);
        return stored;
    }

    private static UUID parseOrGenerateUuid(String maybeUuid) {
        try {
            if (maybeUuid == null || maybeUuid.isBlank()) return UUID.randomUUID();
            return UUID.fromString(maybeUuid);
        } catch (IllegalArgumentException ex) {
            return UUID.randomUUID();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize attributes to JSON", ex);
        }
    }
}

package com.obsinity.service.storage.impl;

import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcEventIngestService implements EventIngestService {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEventIngestService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int ingestOne(EventEnvelope e) {
        // Fallbacks according to the new model
        final UUID eventId = UUID.fromString(e.getEventId()); // ensure it's a real UUID
        final OffsetDateTime tsZ = OffsetDateTime.ofInstant(e.getTimestamp(), ZoneOffset.UTC);
        final String type = e.getName(); // "type" = event name
        final String serviceId = e.getServiceId(); // tenant = service

        // Attributes JSON (just the span-level attributes)
        final Map<String, Object> attrs =
                (e.getAttributes() != null && e.getAttributes().map() != null)
                        ? e.getAttributes().map()
                        : Map.of();

        String sql = "insert into events_raw("
                + "  event_id, ts, type, service_id, trace_id, span_id, correlation_id, attributes"
                + ") values ("
                + "  :event_id, :ts, :type, :service_id, :trace_id, :span_id, :correlation_id, cast(:attributes as jsonb)"
                + ") on conflict (event_id) do nothing";

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("event_id", eventId) // UUID
                .addValue("ts", tsZ, Types.TIMESTAMP_WITH_TIMEZONE) // timestamptz
                .addValue("type", type)
                .addValue("service_id", serviceId) // tenant == service
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
}

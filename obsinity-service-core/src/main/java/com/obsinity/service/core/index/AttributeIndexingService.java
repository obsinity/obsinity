package com.obsinity.service.core.index;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indexes per-event attributes into the DATA table event_attr_index (partitioned).
 * This service reads the list of attribute paths from AttributeIndexSpecCfgRepository.
 */
@Service
public class AttributeIndexingService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AttributeIndexSpecCfgRepository specRepo;

    public AttributeIndexingService(NamedParameterJdbcTemplate jdbc, AttributeIndexSpecCfgRepository specRepo) {
        this.jdbc = jdbc;
        this.specRepo = specRepo;
    }

    @Transactional
    public void indexEvent(EventForIndex evt) throws DataAccessException {
        List<String> paths = specRepo.findIndexedAttributePaths(evt.serviceId(), evt.eventTypeId());
        if (paths == null || paths.isEmpty()) {
            return;
        }

        List<IndexRow> rows = new ArrayList<>(paths.size() * 2);
        for (String path : paths) {
            List<Object> values = extractValuesByPath(evt.attributes(), path);
            if (values.isEmpty()) continue;

            for (Object v : values) {
                String canon = normalizeToText(v);
                if (canon == null || canon.isBlank()) continue;

                rows.add(new IndexRow(
                        evt.serviceShort(),
                        evt.occurredAt(),
                        evt.serviceId(),
                        evt.eventTypeId(),
                        evt.eventId(),
                        path,
                        canon));
            }
        }

        // Inject registry metadata into attribute index for cross-event search
        Map<String, String> meta = loadEventCategories(evt.eventTypeId());
        if (!meta.isEmpty()) {
            for (Map.Entry<String, String> en : meta.entrySet()) {
                rows.add(new IndexRow(
                        evt.serviceShort(),
                        evt.occurredAt(),
                        evt.serviceId(),
                        evt.eventTypeId(),
                        evt.eventId(),
                        en.getKey(),
                        en.getValue()));
            }
        }

        if (!rows.isEmpty()) {
            batchInsert(rows);
        }
    }

    private void batchInsert(List<IndexRow> rows) {
        final String sql =
                """
            INSERT INTO event_attr_index
              (service_short, occurred_at, service_id, event_type_id, event_id, attr_name, attr_value)
            VALUES
              (:service_short, :occurred_at, :service_id, :event_type_id, :event_id, :attr_name, :attr_value)
            ON CONFLICT DO NOTHING
            """;

        MapSqlParameterSource[] batch = rows.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("service_short", r.serviceShort)
                        .addValue("occurred_at", r.occurredAt)
                        .addValue("service_id", r.serviceId)
                        .addValue("event_type_id", r.eventTypeId)
                        .addValue("event_id", r.eventId)
                        .addValue("attr_name", r.attrName)
                        .addValue("attr_value", r.attrValue))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate(sql, batch);
    }

    public interface EventForIndex {
        String serviceShort();

        java.util.UUID serviceId();

        java.util.UUID eventTypeId();

        java.util.UUID eventId();

        OffsetDateTime occurredAt();

        Map<String, Object> attributes();
    }

    private record IndexRow(
            String serviceShort,
            OffsetDateTime occurredAt,
            java.util.UUID serviceId,
            java.util.UUID eventTypeId,
            java.util.UUID eventId,
            String attrName,
            String attrValue) {}

    @SuppressWarnings("unchecked")
    private static List<Object> extractValuesByPath(Map<String, Object> root, String dotPath) {
        if (root == null || dotPath == null || dotPath.isBlank()) return List.of();
        String[] parts = dotPath.split("\\.");
        List<Object> current = List.of(root);

        for (String part : parts) {
            List<Object> next = new ArrayList<>();
            for (Object node : current) {
                if (node instanceof Map<?, ?> m) {
                    Object val = m.get(part);
                    if (val == null) continue;
                    if (val instanceof List<?> list) {
                        next.addAll(list);
                    } else {
                        next.add(val);
                    }
                } else if (node instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> mm) {
                            Object val = mm.get(part);
                            if (val == null) continue;
                            if (val instanceof List<?> l2) next.addAll(l2);
                            else next.add(val);
                        }
                    }
                }
            }
            current = next;
            if (current.isEmpty()) break;
        }

        return current.stream()
                .map(v -> (v instanceof Map || v instanceof List) ? stringify(v) : v)
                .toList();
    }

    private Map<String, String> loadEventCategories(java.util.UUID eventTypeId) {
        final String sql = "select category, sub_category from event_registry where id = ?";
        try {
            return jdbc.getJdbcTemplate()
                    .queryForObject(
                            sql,
                            (rs, rowNum) -> {
                                String cat = rs.getString(1);
                                String sub = rs.getString(2);
                                java.util.Map<String, String> m = new java.util.HashMap<>();
                                if (cat != null && !cat.isBlank()) m.put("event.category", cat);
                                if (sub != null && !sub.isBlank()) m.put("event.subCategory", sub);
                                return m;
                            },
                            eventTypeId);
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    private static String normalizeToText(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.length() <= 4096 ? s : s.substring(0, 4096);
    }

    private static String stringify(Object o) {
        if (o == null) return null;
        if (o instanceof Map<?, ?> m) {
            return m.entrySet().stream()
                    .map(e -> e.getKey() + "=" + stringify(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (o instanceof List<?> l) {
            return l.stream().map(AttributeIndexingService::stringify).collect(Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(o);
    }
}

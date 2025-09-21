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
        List<String> paths = loadIndexedPaths(evt);
        if (paths.isEmpty()) return;

        List<IndexRow> rows = buildIndexRows(evt, paths);
        injectEventMeta(evt, rows);
        if (!rows.isEmpty()) persist(rows);
    }

    private List<String> loadIndexedPaths(EventForIndex evt) {
        List<String> paths = specRepo.findIndexedAttributePaths(evt.serviceId(), evt.eventTypeId());
        return (paths == null) ? List.of() : paths;
    }

    private List<IndexRow> buildIndexRows(EventForIndex evt, List<String> paths) {
        List<IndexRow> rows = new ArrayList<>(paths.size() * 2);
        for (String path : paths) addRowsForPath(rows, evt, path);
        return rows;
    }

    private void addRowsForPath(List<IndexRow> rows, EventForIndex evt, String path) {
        List<Object> values = extractValuesByPath(evt.attributes(), path);
        if (values.isEmpty()) return;
        for (Object v : values) {
            String canon = normalizeToText(v);
            if (canon == null || canon.isBlank()) continue;
            rows.add(new IndexRow(
                    evt.serviceKey(),
                    evt.occurredAt(),
                    evt.serviceId(),
                    evt.eventTypeId(),
                    evt.eventId(),
                    path,
                    canon));
        }
    }

    private void injectEventMeta(EventForIndex evt, List<IndexRow> rows) {
        Map<String, String> meta = loadEventCategories(evt.eventTypeId());
        if (meta.isEmpty()) return;
        for (Map.Entry<String, String> en : meta.entrySet()) {
            rows.add(new IndexRow(
                    evt.serviceKey(),
                    evt.occurredAt(),
                    evt.serviceId(),
                    evt.eventTypeId(),
                    evt.eventId(),
                    en.getKey(),
                    en.getValue()));
        }
    }

    private void persist(List<IndexRow> rows) {
        final String sql =
                """
            INSERT INTO event_attr_index
              (service_short, occurred_at, service_id, event_type_id, event_id, attr_name, attr_value)
            VALUES
              (:service_short, :occurred_at, :service_id, :event_type_id, :event_id, :attr_name, :attr_value)
            ON CONFLICT DO NOTHING
            """;

        MapSqlParameterSource[] batch = rows.stream().map(this::toParams).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
        upsertDistinctValues(rows);
    }

    private MapSqlParameterSource toParams(IndexRow r) {
        return new MapSqlParameterSource()
                .addValue("service_short", r.serviceKey)
                .addValue("occurred_at", r.occurredAt)
                .addValue("service_id", r.serviceId)
                .addValue("event_type_id", r.eventTypeId)
                .addValue("event_id", r.eventId)
                .addValue("attr_name", r.attrName)
                .addValue("attr_value", r.attrValue);
    }

    private void upsertDistinctValues(List<IndexRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        record Key(String s, String n, String v) {}
        Map<Key, OffsetDateTime> uniques = new java.util.LinkedHashMap<>();
        for (IndexRow r : rows) {
            Key k = new Key(r.serviceKey, r.attrName, r.attrValue);
            uniques.merge(k, r.occurredAt, (a, b) -> a.isAfter(b) ? a : b);
        }

        final String sql =
                """
            INSERT INTO attribute_distinct_values (service_short, attr_name, attr_value, first_seen, last_seen, seen_count)
            VALUES (:service_short, :attr_name, :attr_value, :first_seen, :last_seen, :delta)
            ON CONFLICT (service_short, attr_name, attr_value) DO UPDATE
              SET last_seen = GREATEST(attribute_distinct_values.last_seen, EXCLUDED.last_seen),
                  seen_count = attribute_distinct_values.seen_count + EXCLUDED.seen_count
            """;

        MapSqlParameterSource[] batch = uniques.entrySet().stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("service_short", e.getKey().s())
                        .addValue("attr_name", e.getKey().n())
                        .addValue("attr_value", e.getKey().v())
                        .addValue("first_seen", e.getValue())
                        .addValue("last_seen", e.getValue())
                        .addValue("delta", 1))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    public interface EventForIndex {
        String serviceKey();

        java.util.UUID serviceId();

        java.util.UUID eventTypeId();

        java.util.UUID eventId();

        OffsetDateTime occurredAt();

        Map<String, Object> attributes();
    }

    private record IndexRow(
            String serviceKey,
            OffsetDateTime occurredAt,
            java.util.UUID serviceId,
            java.util.UUID eventTypeId,
            java.util.UUID eventId,
            String attrName,
            String attrValue) {}

    @SuppressWarnings("unchecked")
    private static List<Object> extractValuesByPath(Map<String, Object> root, String dotPath) {
        if (root == null || dotPath == null || dotPath.isBlank()) return List.of();
        List<Object> current = List.of(root);
        for (String part : dotPath.split("\\.")) {
            List<Object> next = new ArrayList<>();
            descendOneLevel(current, part, next);
            if (next.isEmpty()) return List.of();
            current = next;
        }
        return current.stream()
                .map(v -> (v instanceof Map || v instanceof List) ? stringify(v) : v)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static void descendOneLevel(List<Object> nodes, String key, List<Object> out) {
        for (Object node : nodes) addMatchesForKey(node, key, out);
    }

    @SuppressWarnings("unchecked")
    private static void addMatchesForKey(Object node, String key, List<Object> out) {
        if (node instanceof Map<?, ?> m) {
            addValue(m.get(key), out);
            return;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) addMatchesForKey(item, key, out);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addValue(Object v, List<Object> out) {
        if (v == null) return;
        if (v instanceof List<?> l) out.addAll(l);
        else out.add(v);
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

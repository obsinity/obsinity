package com.obsinity.service.core.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.EventTypeConfig;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indexes per-event attributes into the DATA table event_attr_index (partitioned).
 * Configuration is sourced from the in-memory ConfigRegistry via ConfigLookup.
 */
@Service
public class AttributeIndexingService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigLookup configLookup;

    public AttributeIndexingService(NamedParameterJdbcTemplate jdbc, ConfigLookup configLookup) {
        this.jdbc = jdbc;
        this.configLookup = configLookup;
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
        return configLookup
                .get(evt.serviceId(), evt.eventType())
                .map(EventTypeConfig::indexes)
                .map(list -> list.stream()
                        .flatMap(idx -> extractPaths(idx.definition()).stream())
                        .distinct()
                        .toList())
                .orElse(List.of());
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
                    evt.servicePartitionKey(),
                    evt.startedAt(),
                    evt.serviceId(),
                    evt.eventTypeId(),
                    evt.eventId(),
                    path,
                    canon));
        }
    }

    private void injectEventMeta(EventForIndex evt, List<IndexRow> rows) {
        Map<String, String> meta = configLookup
                .get(evt.serviceId(), evt.eventType())
                .map(this::extractCategories)
                .orElse(Map.of());
        if (meta.isEmpty()) return;
        for (Map.Entry<String, String> en : meta.entrySet()) {
            rows.add(new IndexRow(
                    evt.servicePartitionKey(),
                    evt.startedAt(),
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
              (service_partition_key, started_at, service_id, event_type_id, event_id, attr_name, attr_value)
            VALUES
              (:service_partition_key, :started_at, :service_id, :event_type_id, :event_id, :attr_name, :attr_value)
            ON CONFLICT DO NOTHING
            """;

        MapSqlParameterSource[] batch = rows.stream().map(this::toParams).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
        upsertDistinctValues(rows);
    }

    private MapSqlParameterSource toParams(IndexRow r) {
        return new MapSqlParameterSource()
                .addValue("service_partition_key", r.servicePartitionKey())
                .addValue("started_at", r.startedAt())
                .addValue("service_id", r.serviceId())
                .addValue("event_type_id", r.eventTypeId())
                .addValue("event_id", r.eventId())
                .addValue("attr_name", r.attrName())
                .addValue("attr_value", r.attrValue());
    }

    private void upsertDistinctValues(List<IndexRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        record Key(String s, String n, String v) {}
        Map<Key, OffsetDateTime> uniques = new HashMap<>();
        for (IndexRow r : rows) {
            Key k = new Key(r.servicePartitionKey(), r.attrName(), r.attrValue());
            uniques.merge(k, r.startedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }

        final String sql =
                """
            INSERT INTO attribute_distinct_values (service_partition_key, attr_name, attr_value, first_seen, last_seen, seen_count)
            VALUES (:service_partition_key, :attr_name, :attr_value, :first_seen, :last_seen, :delta)
            ON CONFLICT (service_partition_key, attr_name, attr_value) DO UPDATE
              SET last_seen = GREATEST(attribute_distinct_values.last_seen, EXCLUDED.last_seen),
                  seen_count = attribute_distinct_values.seen_count + EXCLUDED.seen_count
            """;

        MapSqlParameterSource[] batch = uniques.entrySet().stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("service_partition_key", e.getKey().s())
                        .addValue("attr_name", e.getKey().n())
                        .addValue("attr_value", e.getKey().v())
                        .addValue("first_seen", e.getValue())
                        .addValue("last_seen", e.getValue())
                        .addValue("delta", 1))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    public interface EventForIndex {
        String servicePartitionKey();

        java.util.UUID serviceId();

        java.util.UUID eventTypeId();

        String eventType();

        java.util.UUID eventId();

        OffsetDateTime startedAt();

        Map<String, Object> attributes();
    }

    private record IndexRow(
            String servicePartitionKey,
            OffsetDateTime startedAt,
            java.util.UUID serviceId,
            java.util.UUID eventTypeId,
            java.util.UUID eventId,
            String attrName,
            String attrValue) {}

    private Map<String, String> extractCategories(EventTypeConfig config) {
        Map<String, String> map = new HashMap<>(2);
        if (config.category() != null && !config.category().isBlank()) {
            map.put("event.category", config.category());
        }
        if (config.subCategory() != null && !config.subCategory().isBlank()) {
            map.put("event.subCategory", config.subCategory());
        }
        return map;
    }

    private List<String> extractPaths(JsonNode definition) {
        if (definition == null || definition.isNull()) return List.of();
        JsonNode node = definition.path("indexed");
        if (node.isMissingNode()) return List.of();
        List<String> paths = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> {
                if (n.isTextual()) paths.add(n.asText());
            });
        } else if (node.isTextual()) {
            paths.add(node.asText());
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> extractValuesByPath(Map<String, Object> root, String dotPath) {
        if (root == null || dotPath == null || dotPath.isBlank()) return List.of();
        if (root.containsKey(dotPath)) {
            Object direct = root.get(dotPath);
            return direct == null ? List.of() : List.of(direct);
        }
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

package com.obsinity.service.core.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal columnar view that mirrors the Frictionless Data tabular resource shape. */
public final class FrictionlessData {

    private FrictionlessData() {}

    public record Table(Schema schema, Map<String, List<Object>> data) {}

    public record Schema(List<Field> fields) {}

    public record Field(String name, String type) {}

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static Table columnar(List<?> rows, ObjectMapper mapper) {
        if (rows == null || rows.isEmpty()) {
            return new Table(new Schema(List.of()), Map.of());
        }

        List<Map<String, Object>> maps = new ArrayList<>(rows.size());
        for (Object row : rows) {
            maps.add(toMap(row, mapper));
        }

        LinkedHashMap<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map<String, Object> row : maps) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String existing = fieldTypes.get(entry.getKey());
                String detected = detectType(entry.getValue());
                fieldTypes.put(entry.getKey(), mergeTypes(existing, detected));
            }
        }

        Map<String, List<Object>> columns = new LinkedHashMap<>();
        for (String key : fieldTypes.keySet()) {
            columns.put(key, new ArrayList<>(Collections.nCopies(maps.size(), null)));
        }

        for (int i = 0; i < maps.size(); i++) {
            Map<String, Object> row = maps.get(i);
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                List<Object> col = columns.get(entry.getKey());
                if (col != null) {
                    col.set(i, entry.getValue());
                }
            }
        }

        List<Field> fields = fieldTypes.entrySet().stream()
                .map(e -> new Field(e.getKey(), e.getValue()))
                .toList();
        return new Table(new Schema(fields), columns);
    }

    private static Map<String, Object> toMap(Object value, ObjectMapper mapper) {
        if (value instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mm = (Map<String, Object>) m;
            return mm;
        }
        return mapper.convertValue(value, MAP_TYPE);
    }

    private static String detectType(Object value) {
        if (value == null) {
            return "any";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Instant
                || value instanceof OffsetDateTime
                || value instanceof ZonedDateTime
                || value instanceof LocalDateTime
                || value instanceof LocalDate) {
            return "datetime";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof Enum<?>) {
            return "string";
        }
        return "string";
    }

    private static String mergeTypes(String existing, String detected) {
        if (existing == null) {
            return detected;
        }
        if (existing.equals(detected)) {
            return existing;
        }
        if (("integer".equals(existing) && "number".equals(detected))
                || ("number".equals(existing) && "integer".equals(detected))) {
            return "number";
        }
        return "any";
    }
}

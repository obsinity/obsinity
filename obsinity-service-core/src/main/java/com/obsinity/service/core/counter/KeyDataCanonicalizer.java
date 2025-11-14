package com.obsinity.service.core.counter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** Utility to produce stable JSON encodings for counter key data. */
final class KeyDataCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KeyDataCanonicalizer() {}

    static String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        ObjectNode node = MAPPER.createObjectNode();
        map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> node.put(entry.getKey(), entry.getValue()));
        return node.toString();
    }
}

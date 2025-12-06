package com.obsinity.flow.validation;

import java.util.Map;

/**
 * Validates flow attributes and context values to prevent common issues
 * like storing entities, large objects, or non-serializable data.
 */
public interface FlowAttributeValidator {

    /**
     * Validate a single attribute or context value.
     *
     * @param key the attribute/context key
     * @param value the value to validate
     * @throws IllegalArgumentException if validation fails
     */
    void validate(String key, Object value);

    /**
     * Validate all entries in a map.
     *
     * @param map the map to validate
     * @param mapName the name of the map (for error messages)
     * @throws IllegalArgumentException if any value fails validation
     */
    default void validateMap(Map<String, Object> map, String mapName) {
        if (map == null || map.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            try {
                validate(entry.getKey(), entry.getValue());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(String.format("Invalid %s entry: %s", mapName, e.getMessage()), e);
            }
        }
    }
}

package com.obsinity.service.core.model;

import java.util.Collections;
import java.util.Map;

/** Basic holder for a flow or step event. Expand as needed. */
public record TelemetryHolder(String name, Map<String, Object> attributes, Map<String, Object> context) {
    public static TelemetryHolder of(String name) {
        return new TelemetryHolder(name, Collections.emptyMap(), Collections.emptyMap());
    }
}

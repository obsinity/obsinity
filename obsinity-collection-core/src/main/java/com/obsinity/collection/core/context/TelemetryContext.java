package com.obsinity.collection.core.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deprecated thread-local context used by the collection SDK pipeline.
 * Prefer {@code com.obsinity.telemetry.processor.TelemetryContext} for service-side processing.
 */
@Deprecated
public final class TelemetryContext {
    private static final ThreadLocal<Map<String, Object>> ATTRS = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<Map<String, Object>> CTX = ThreadLocal.withInitial(LinkedHashMap::new);

    private TelemetryContext() {}

    public static void putAttr(String key, Object value) {
        ATTRS.get().put(key, value);
    }

    public static void putContext(String key, Object value) {
        CTX.get().put(key, value);
    }

    public static Map<String, Object> snapshotAttrs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(ATTRS.get()));
    }

    public static Map<String, Object> snapshotContext() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(CTX.get()));
    }

    public static void clear() {
        ATTRS.get().clear();
        CTX.get().clear();
    }
}

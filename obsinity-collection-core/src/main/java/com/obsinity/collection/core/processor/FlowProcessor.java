package com.obsinity.collection.core.processor;

import java.util.Map;

public interface FlowProcessor {
    default void onFlowStarted(String name) {
        onFlowStarted(name, Map.of(), Map.of());
    }

    void onFlowStarted(String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext);

    default void onFlowCompleted(String name) {
        onFlowCompleted(name, Map.of(), Map.of());
    }

    void onFlowCompleted(String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext);

    default void onFlowFailed(String name, Throwable error) {
        onFlowFailed(name, error, Map.of(), Map.of());
    }

    void onFlowFailed(String name, Throwable error, Map<String, Object> extraAttrs, Map<String, Object> extraContext);

    // Meta-aware overloads
    default void onFlowStarted(
            String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext, FlowMeta meta) {
        onFlowStarted(name, extraAttrs, extraContext);
    }

    default void onFlowCompleted(
            String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext, FlowMeta meta) {
        onFlowCompleted(name, extraAttrs, extraContext);
    }

    default void onFlowFailed(
            String name,
            Throwable error,
            Map<String, Object> extraAttrs,
            Map<String, Object> extraContext,
            FlowMeta meta) {
        onFlowFailed(name, error, extraAttrs, extraContext);
    }
}

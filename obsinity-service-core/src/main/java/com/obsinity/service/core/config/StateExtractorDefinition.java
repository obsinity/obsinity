package com.obsinity.service.core.config;

import java.util.List;

/**
 * Materialized definition of a state extractor derived from the service config.
 */
public record StateExtractorDefinition(
        String rawType,
        String objectType,
        String objectIdField,
        List<String> stateAttributes,
        List<String> transitionOnlyFromStates,
        List<String> transitionAdditionalFromStates) {

    public StateExtractorDefinition(
            String rawType,
            String objectType,
            String objectIdField,
            List<String> stateAttributes,
            List<String> transitionFromStates) {
        this(rawType, objectType, objectIdField, stateAttributes, List.of(), transitionFromStates);
    }
}

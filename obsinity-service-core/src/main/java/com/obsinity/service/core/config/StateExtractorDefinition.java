package com.obsinity.service.core.config;

import java.util.List;

/**
 * Materialized definition of a state extractor derived from the service config.
 */
public record StateExtractorDefinition(
        String rawType, String objectType, String objectIdField, List<String> stateAttributes) {}

package com.obsinity.service.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Immutable persistent counter configuration materialised from inbound service config. */
public record PersistentCounterConfig(
        UUID id,
        String name,
        List<String> keyedKeys,
        PersistentCounterOperation operation,
        boolean floorAtZero,
        JsonNode definition,
        JsonNode filters) {}

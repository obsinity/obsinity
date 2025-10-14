package com.obsinity.service.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.obsinity.service.core.counter.CounterGranularity;
import java.util.List;
import java.util.UUID;

/** Immutable counter metric configuration materialised from inbound service config. */
public record CounterConfig(
        UUID id,
        String name,
        CounterGranularity granularity,
        List<String> keyedKeys,
        JsonNode definition,
        JsonNode filters) {}

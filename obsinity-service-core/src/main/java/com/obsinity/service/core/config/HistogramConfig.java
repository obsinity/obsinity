package com.obsinity.service.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Immutable histogram metric configuration. */
public record HistogramConfig(UUID id, String name, JsonNode definition) {}

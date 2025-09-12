package com.obsinity.service.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Immutable counter metric configuration. */
public record CounterConfig(UUID id, String name, JsonNode definition) {}

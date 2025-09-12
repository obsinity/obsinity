package com.obsinity.service.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Immutable index specification for attribute indexing. */
public record IndexConfig(UUID id, String name, JsonNode definition) {}

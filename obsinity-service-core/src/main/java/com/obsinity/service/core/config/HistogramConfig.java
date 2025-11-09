package com.obsinity.service.core.config;

import java.util.UUID;

/** Immutable histogram metric configuration. */
public record HistogramConfig(UUID id, String name, HistogramSpec spec) {}

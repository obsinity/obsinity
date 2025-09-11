package com.obsinity.service.core.model.config;

import java.util.Map;
import java.util.UUID;

/** Optional attribute index for the event. */
public record EventIndexConfig(
        UUID uuid,
        Map<String, Object> specJson, // e.g., {"indexed":["service","method","status_code"]}
        String specHash) {}

package com.obsinity.service.core.model.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A single metric defined under an event. */
public record MetricConfig(
        UUID uuid, // optional; server still computes deterministic metric_key
        String name,
        String type, // COUNTER | HISTOGRAM | GAUGE | STATE_COUNTER
        Map<String, Object> specJson,
        String specHash,
        List<String> keyedKeys,
        List<String> rollups,
        Map<String, Object> filtersJson,
        String bucketLayoutHash, // histograms only
        String backfillWindow, // e.g., "7 days"
        Instant cutoverAt,
        Instant graceUntil,
        String state // PENDING | ACTIVE | etc. (server may override)
        ) {}

package com.obsinity.service.core.model.config;

/** Response returned by the ingest endpoint. */
public record ServiceConfigResponse(
        String snapshotId, boolean applied, int eventsUpserted, int metricsUpserted, int attributeIndexesUpserted) {}

package com.obsinity.controller.rest.grafana;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrafanaBucketResolverTest {

    @Test
    void usesExplicitBucketWhenProvided() {
        String bucket = GrafanaBucketResolver.resolveBucket(
                "5m", 1000L, 10, 0L, 60000L, java.util.List.of(java.time.Duration.ofMinutes(1)));
        assertEquals("5m", bucket);
    }

    @Test
    void derivesBucketFromIntervalMs() {
        String bucket = GrafanaBucketResolver.resolveBucket(
                null,
                60000L,
                null,
                0L,
                3600000L,
                java.util.List.of(java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(5)));
        assertEquals("1m", bucket);
    }

    @Test
    void capsPointsToMaxDataPoints() {
        String bucket = GrafanaBucketResolver.resolveBucket(
                null,
                1000L,
                10,
                0L,
                60000L,
                java.util.List.of(java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(1)));
        assertEquals("1m", bucket);
    }
}

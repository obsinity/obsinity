package com.obsinity.controller.rest.grafana;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrafanaBucketResolverTest {

    @Test
    void usesExplicitBucketWhenProvided() {
        String bucket = GrafanaBucketResolver.resolveBucket("5m", 1000L, 10, 0L, 60000L);
        assertEquals("5m", bucket);
    }

    @Test
    void derivesBucketFromIntervalMs() {
        String bucket = GrafanaBucketResolver.resolveBucket(null, 60000L, null, 0L, 3600000L);
        assertEquals("1m", bucket);
    }

    @Test
    void capsPointsToMaxDataPoints() {
        String bucket = GrafanaBucketResolver.resolveBucket(null, 1000L, 10, 0L, 60000L);
        assertEquals("6s", bucket);
    }
}

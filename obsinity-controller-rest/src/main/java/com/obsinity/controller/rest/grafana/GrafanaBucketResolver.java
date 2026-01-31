package com.obsinity.controller.rest.grafana;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

public final class GrafanaBucketResolver {

    private static final long MIN_BUCKET_MS = 1000L;

    private GrafanaBucketResolver() {}

    public static String resolveBucket(
            String requestedBucket,
            Long intervalMs,
            Integer maxDataPoints,
            long fromMs,
            long toMs,
            List<Duration> allowedBuckets) {
        if (requestedBucket != null && !requestedBucket.isBlank()) {
            return requestedBucket;
        }

        long rangeMs = Math.max(0L, toMs - fromMs);
        long bucketMs = intervalMs != null && intervalMs > 0 ? intervalMs : defaultBucketMs(rangeMs, maxDataPoints);

        if (maxDataPoints != null && maxDataPoints > 0 && rangeMs > 0) {
            long maxPoints = Math.max(1, maxDataPoints);
            long idealMs = (long) Math.ceil((double) rangeMs / (double) maxPoints);
            if (idealMs > bucketMs) {
                bucketMs = idealMs;
            }
        }

        bucketMs = Math.max(MIN_BUCKET_MS, bucketMs);
        if (allowedBuckets == null || allowedBuckets.isEmpty()) {
            return toDurationString(bucketMs);
        }
        return toDurationString(selectAllowedBucket(bucketMs, allowedBuckets));
    }

    private static long defaultBucketMs(long rangeMs, Integer maxDataPoints) {
        if (rangeMs <= 0) {
            return Duration.ofMinutes(1).toMillis();
        }
        if (maxDataPoints != null && maxDataPoints > 0) {
            return Math.max(MIN_BUCKET_MS, rangeMs / Math.max(1, maxDataPoints));
        }
        return Duration.ofMinutes(1).toMillis();
    }

    static String toDurationString(long millis) {
        if (millis % Duration.ofDays(7).toMillis() == 0) {
            return (millis / Duration.ofDays(7).toMillis()) + "d";
        }
        if (millis % Duration.ofDays(1).toMillis() == 0) {
            return (millis / Duration.ofDays(1).toMillis()) + "d";
        }
        if (millis % Duration.ofHours(1).toMillis() == 0) {
            return (millis / Duration.ofHours(1).toMillis()) + "h";
        }
        if (millis % Duration.ofMinutes(1).toMillis() == 0) {
            return (millis / Duration.ofMinutes(1).toMillis()) + "m";
        }
        if (millis % Duration.ofSeconds(1).toMillis() == 0) {
            return (millis / Duration.ofSeconds(1).toMillis()) + "s";
        }
        return millis + "ms";
    }

    private static long selectAllowedBucket(long desiredMs, List<Duration> allowedBuckets) {
        List<Duration> sorted = allowedBuckets.stream()
                .sorted(Comparator.comparingLong(Duration::toMillis))
                .toList();
        for (Duration duration : sorted) {
            if (desiredMs <= duration.toMillis()) {
                return duration.toMillis();
            }
        }
        return sorted.get(sorted.size() - 1).toMillis();
    }
}

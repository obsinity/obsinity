package com.obsinity.service.core.counter;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Supported counter buckets. Includes ingest granularity (5s,1m,5m) and rollups (1h,1d,7d).
 */
public enum CounterBucket {
    D7("D7", Duration.ofDays(7)),
    D1("D1", Duration.ofDays(1)),
    M30("M30", Duration.ofMinutes(30)),
    H1("H1", Duration.ofHours(1)),
    M5("M5", Duration.ofMinutes(5)),
    M1("M1", Duration.ofMinutes(1)),
    S5("S5", Duration.ofSeconds(5));

    private final String label;
    private final Duration duration;

    CounterBucket(String label, Duration duration) {
        this.label = label;
        this.duration = duration;
    }

    public String label() {
        return label;
    }

    public Duration duration() {
        return duration;
    }

    /** Aligns the instant to the start of the containing bucket. */
    public Instant align(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
        return switch (this) {
            case D7 -> {
                ZonedDateTime startOfWeek = zdt.minusDays((zdt.getDayOfWeek().getValue() + 6) % 7)
                        .toLocalDate()
                        .atStartOfDay(ZoneOffset.UTC);
                yield startOfWeek.toInstant();
            }
            case D1 -> zdt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            case H1 -> zdt.withMinute(0).withSecond(0).withNano(0).toInstant();
            case M5 -> {
                int minute = zdt.getMinute();
                int aligned = (minute / 5) * 5;
                yield zdt.withMinute(aligned).withSecond(0).withNano(0).toInstant();
            }
            case M30 -> {
                int minute = zdt.getMinute();
                int aligned = (minute / 30) * 30;
                yield zdt.withMinute(aligned).withSecond(0).withNano(0).toInstant();
            }
            case M1 -> zdt.withSecond(0).withNano(0).toInstant();
            case S5 -> {
                int second = zdt.getSecond();
                int alignedSecond = (second / 5) * 5;
                yield zdt.withSecond(alignedSecond).withNano(0).toInstant();
            }
        };
    }

    /** Aligns the instant down to the most recent aligned boundary. */
    public Instant alignToFloor(Instant instant) {
        long bucketMillis = duration.toMillis();
        long epochMillis = instant.toEpochMilli();
        long alignedMillis = (epochMillis / bucketMillis) * bucketMillis;
        return Instant.ofEpochMilli(alignedMillis);
    }

    /** Aligns the instant up to the next aligned boundary (if not already aligned). */
    public Instant alignToNext(Instant instant) {
        long bucketMillis = duration.toMillis();
        long epochMillis = instant.toEpochMilli();
        long alignedMillis = ((epochMillis + bucketMillis - 1) / bucketMillis) * bucketMillis;
        return Instant.ofEpochMilli(alignedMillis);
    }

    public static List<CounterBucket> valuesSortedByDescendingDuration() {
        return Arrays.stream(values())
                .sorted(Comparator.comparing(CounterBucket::duration).reversed())
                .toList();
    }

    public static List<CounterBucket> valuesSortedByAscendingDuration() {
        return Arrays.stream(values())
                .sorted(Comparator.comparing(CounterBucket::duration))
                .toList();
    }
}

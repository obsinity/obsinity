package com.obsinity.service.core.counter;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Base granularity supported for counter ingestion buffers.
 */
public enum CounterGranularity {
    S5(CounterBucket.S5),
    M1(CounterBucket.M1),
    M5(CounterBucket.M5);

    private final CounterBucket baseBucket;

    CounterGranularity(CounterBucket baseBucket) {
        this.baseBucket = baseBucket;
    }

    public CounterBucket baseBucket() {
        return baseBucket;
    }

    public Duration duration() {
        return baseBucket.duration();
    }

    /** Buckets that must be materialised when flushing this granularity. */
    public EnumSet<CounterBucket> materialisedBuckets() {
        return switch (this) {
            case S5 -> EnumSet.of(
                    CounterBucket.S5,
                    CounterBucket.M1,
                    CounterBucket.M5,
                    CounterBucket.H1,
                    CounterBucket.D1,
                    CounterBucket.D7);
            case M1 -> EnumSet.of(
                    CounterBucket.M1, CounterBucket.M5, CounterBucket.H1, CounterBucket.D1, CounterBucket.D7);
            case M5 -> EnumSet.of(CounterBucket.M5, CounterBucket.H1, CounterBucket.D1, CounterBucket.D7);
        };
    }

    public static CounterGranularity fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return S5;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "5S", "S5" -> S5;
            case "1M", "M1", "60S" -> M1;
            case "5M", "M5" -> M5;
            default -> throw new IllegalArgumentException("Unsupported counter granularity: " + value);
        };
    }
}

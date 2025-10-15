package com.obsinity.service.core.counter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CounterGranularityTest {

    @Test
    void fromConfigValueDefaultsToS5() {
        assertEquals(CounterGranularity.S5, CounterGranularity.fromConfigValue(null));
        assertEquals(CounterGranularity.S5, CounterGranularity.fromConfigValue(" "));
    }

    @Test
    void fromConfigValueParsesAliases() {
        assertEquals(CounterGranularity.S5, CounterGranularity.fromConfigValue("5s"));
        assertEquals(CounterGranularity.M1, CounterGranularity.fromConfigValue("1m"));
        assertEquals(CounterGranularity.M5, CounterGranularity.fromConfigValue("5M"));
    }

    @Test
    void fromConfigValueRejectsUnsupported() {
        assertThrows(IllegalArgumentException.class, () -> CounterGranularity.fromConfigValue("2s"));
    }

    @Test
    void materialisedBucketsContainRollups() {
        EnumSet<CounterBucket> s5Buckets = CounterGranularity.S5.materialisedBuckets();
        assertEquals(
                EnumSet.of(
                        CounterBucket.S5,
                        CounterBucket.M1,
                        CounterBucket.M5,
                        CounterBucket.H1,
                        CounterBucket.D1,
                        CounterBucket.D7),
                s5Buckets);

        EnumSet<CounterBucket> m1Buckets = CounterGranularity.M1.materialisedBuckets();
        assertEquals(
                EnumSet.of(CounterBucket.M1, CounterBucket.M5, CounterBucket.H1, CounterBucket.D1, CounterBucket.D7),
                m1Buckets);

        EnumSet<CounterBucket> m5Buckets = CounterGranularity.M5.materialisedBuckets();
        assertEquals(EnumSet.of(CounterBucket.M5, CounterBucket.H1, CounterBucket.D1, CounterBucket.D7), m5Buckets);
    }

    @Test
    void durationMatchesBaseBucket() {
        assertEquals(Duration.ofSeconds(5), CounterGranularity.S5.duration());
        assertEquals(Duration.ofMinutes(1), CounterGranularity.M1.duration());
        assertEquals(Duration.ofMinutes(5), CounterGranularity.M5.duration());
    }
}

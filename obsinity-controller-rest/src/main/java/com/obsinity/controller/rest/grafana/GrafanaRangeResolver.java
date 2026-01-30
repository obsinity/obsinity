package com.obsinity.controller.rest.grafana;

import java.time.Instant;
import java.time.Duration;

public final class GrafanaRangeResolver {

    private GrafanaRangeResolver() {}

    public record ResolvedRange(Instant from, Instant to, long fromMs, long toMs) {}

    public static ResolvedRange resolve(GrafanaQueryModels.Range range) {
        Instant now = Instant.now();
        if (range == null) {
            Instant from = now.minus(Duration.ofHours(1));
            return new ResolvedRange(from, now, from.toEpochMilli(), now.toEpochMilli());
        }

        if (range.fromMs() != null && range.toMs() != null) {
            Instant from = Instant.ofEpochMilli(range.fromMs());
            Instant to = Instant.ofEpochMilli(range.toMs());
            return new ResolvedRange(from, to, range.fromMs(), range.toMs());
        }

        if (range.from() != null && range.to() != null) {
            Instant from = Instant.parse(range.from());
            Instant to = Instant.parse(range.to());
            return new ResolvedRange(from, to, from.toEpochMilli(), to.toEpochMilli());
        }

        Instant from = now.minus(Duration.ofHours(1));
        return new ResolvedRange(from, now, from.toEpochMilli(), now.toEpochMilli());
    }
}

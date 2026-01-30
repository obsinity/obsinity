package com.obsinity.controller.rest.grafana;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.obsinity.controller.rest.grafana.GrafanaQueryModels.Range;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GrafanaRangeResolverTest {

    @Test
    void prefersMillisRangeWhenPresent() {
        Instant fromIso = Instant.parse("2026-01-30T09:00:00Z");
        Instant toIso = Instant.parse("2026-01-30T10:00:00Z");
        Range range = new Range(fromIso.toString(), toIso.toString(), 1000L, 2000L);

        GrafanaRangeResolver.ResolvedRange resolved = GrafanaRangeResolver.resolve(range);

        assertEquals(1000L, resolved.fromMs());
        assertEquals(2000L, resolved.toMs());
    }
}

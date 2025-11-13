package com.obsinity.reference.api;

import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo")
@RequiredArgsConstructor
public class SampleDataController {

    private final EventIngestService ingestService;

    @PostMapping("/generate-latency")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> generateLatencyData(@RequestBody(required = false) SampleRequest request) {
        SampleRequest req = SampleRequest.defaults(request);
        Instant requestTime = Instant.now();
        Instant startOfCurrentDay = requestTime
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        List<EventEnvelope> events = new ArrayList<>();
        double[] latencyProfile = {50, 65, 90, 120, 160, 210, 280, 360, 450, 560, 700, 900, 1150, 1400};
        int statusesSize = req.statusCodes().size();
        int eventsPerDay = Math.max(1, req.eventsPerDay());
        long millisPerSlot = Duration.ofDays(1).toMillis() / eventsPerDay;

        for (int day = 0; day < req.days(); day++) {
            long dayOffset = req.days() - 1L - day;
            Instant dayStart = startOfCurrentDay.minus(Duration.ofDays(dayOffset));
            Instant dayEndExclusive = dayStart.plus(Duration.ofDays(1));
            Instant upperBound = dayOffset == 0 ? requestTime : dayEndExclusive;
            if (!dayStart.isBefore(upperBound)) {
                continue;
            }
            for (int slot = 0; slot < eventsPerDay; slot++) {
                Instant start = dayStart.plusMillis(slot * millisPerSlot);
                if (!start.isBefore(upperBound)) {
                    break;
                }
                double baseLatency = latencyProfile[slot % latencyProfile.length];
                boolean spike = ((day * eventsPerDay) + slot) % 50 == 0;
                if (spike) {
                    baseLatency = 1600 + (slot % 10) * 100;
                }
                double jitterFactor = 0.9 + ((day % 4) * 0.05);
                long latencyMillis = Math.max(25, Math.round(baseLatency * jitterFactor));
                Instant end = start.plusMillis(latencyMillis);
                if (end.isAfter(upperBound)) {
                    end = upperBound;
                }
                if (!end.isAfter(start)) {
                    continue;
                }
                String status = req.statusCodes().get((day + slot) % statusesSize);
                events.add(buildEvent(req, start, end, status));
            }
        }

        int stored = ingestService.ingestBatch(events);
        return Map.of(
                "generated", events.size(),
                "stored", stored,
                "service", req.serviceKey(),
                "eventType", req.eventType());
    }

    private EventEnvelope buildEvent(SampleRequest req, Instant start, Instant end, String status) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("environment", req.environment());

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", req.httpMethod());
        http.put("route", req.httpRoute());
        http.put("status", status);

        Map<String, Object> api = new LinkedHashMap<>();
        api.put("name", req.apiName());
        api.put("version", req.apiVersion());

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("http", http);
        attributes.put("api", api);

        return EventEnvelope.builder()
                .serviceId(req.serviceKey())
                .eventType(req.eventType())
                .eventId(UUID.randomUUID().toString())
                .timestamp(start)
                .endTimestamp(end)
                .ingestedAt(Instant.now())
                .name(req.eventType())
                .kind("SERVER")
                .resourceAttributes(resource)
                .attributes(attributes)
                .build();
    }

    public record SampleRequest(
            String serviceKey,
            String eventType,
            String httpMethod,
            String httpRoute,
            List<String> statusCodes,
            String apiName,
            String apiVersion,
            String environment,
            int days,
            int eventsPerDay) {

        static SampleRequest defaults(SampleRequest maybe) {
            if (maybe == null) {
                return new SampleRequest(
                        "payments",
                        "http_request",
                        "GET",
                        "/api/checkout",
                        List.of("200", "500"),
                        "checkout",
                        "v1",
                        "demo",
                        14,
                        5000);
            }
            return new SampleRequest(
                    emptyToDefault(maybe.serviceKey, "payments"),
                    emptyToDefault(maybe.eventType, "http_request"),
                    emptyToDefault(maybe.httpMethod, "GET"),
                    emptyToDefault(maybe.httpRoute, "/api/checkout"),
                    (maybe.statusCodes == null || maybe.statusCodes.isEmpty())
                            ? List.of("200", "500")
                            : maybe.statusCodes,
                    emptyToDefault(maybe.apiName, "checkout"),
                    emptyToDefault(maybe.apiVersion, "v1"),
                    emptyToDefault(maybe.environment, "demo"),
                    maybe.days <= 0 ? 14 : maybe.days,
                    maybe.eventsPerDay <= 0 ? 5000 : maybe.eventsPerDay);
        }

        private static String emptyToDefault(String value, String fallback) {
            return (value == null || value.isBlank()) ? fallback : value;
        }
    }
}

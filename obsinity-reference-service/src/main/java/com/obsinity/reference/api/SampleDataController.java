package com.obsinity.reference.api;

import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import java.time.Duration;
import java.time.Instant;
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
        Instant now = Instant.now();
        List<EventEnvelope> events = new ArrayList<>();
        double[] latencyProfile = {15, 25, 40, 60, 80, 120, 180, 250, 400, 750, 1100, 1500};
        int statusesSize = req.statusCodes().size();
        long secondsPerDaySlot = Duration.ofDays(1).toSeconds() / Math.max(1, req.eventsPerDay());

        for (int day = 0; day < req.days(); day++) {
            Instant dayStart = now.minus(Duration.ofDays(req.days() - day));
            for (int slot = 0; slot < req.eventsPerDay(); slot++) {
                Instant start = dayStart.plusSeconds(slot * secondsPerDaySlot);
                double baseLatency = latencyProfile[slot % latencyProfile.length];
                double jitterFactor = 1.0 + ((day % 5) * 0.05);
                long latencyMillis = Math.max(5, Math.round(baseLatency * jitterFactor));
                Instant end = start.plusMillis(latencyMillis);
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
                        24);
            }
            return new SampleRequest(
                    emptyToDefault(maybe.serviceKey, "payments"),
                    emptyToDefault(maybe.eventType, "http_request"),
                    emptyToDefault(maybe.httpMethod, "GET"),
                    emptyToDefault(maybe.httpRoute, "/api/checkout"),
                    (maybe.statusCodes == null || maybe.statusCodes.isEmpty()) ? List.of("200", "500") : maybe.statusCodes,
                    emptyToDefault(maybe.apiName, "checkout"),
                    emptyToDefault(maybe.apiVersion, "v1"),
                    emptyToDefault(maybe.environment, "demo"),
                    maybe.days <= 0 ? 14 : maybe.days,
                    maybe.eventsPerDay <= 0 ? 24 : maybe.eventsPerDay);
        }

        List<String> statusCodes() {
            return statusCodes;
        }

        private static String emptyToDefault(String value, String fallback) {
            return (value == null || value.isBlank()) ? fallback : value;
        }
    }
}

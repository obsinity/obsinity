package com.obsinity.reference.api;

import com.obsinity.service.core.counter.CounterFlushService;
import com.obsinity.service.core.counter.CounterGranularity;
import com.obsinity.service.core.histogram.HistogramFlushService;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo")
@RequiredArgsConstructor
@Slf4j
public class SampleDataController {

    private static final int ERROR_FREQUENCY = 20;

    private final EventIngestService ingestService;
    private final CounterFlushService counterFlushService;
    private final HistogramFlushService histogramFlushService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "demo-state-generator");
        t.setDaemon(true);
        return t;
    });
    private final Random random = new Random();

    @PostMapping("/generate-latency")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> generateLatencyData(@RequestBody(required = false) SampleRequest request) {
        SampleRequest req = SampleRequest.defaults(request);
        return generateLatencyBatch(req);
    }

    private Map<String, Object> generateLatencyBatch(SampleRequest req) {
        List<EventEnvelope> events = buildHistogramEvents(req);

        int stored = ingestService.ingestBatch(events);
        triggerFlushes();
        return Map.of(
                "generated", events.size(),
                "stored", stored,
                "service", req.serviceKey(),
                "eventType", req.eventType());
    }

    @PostMapping("/state-cascade")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> enqueueStateCascade(@RequestBody(required = false) StateCascadeRequest maybe) {
        StateCascadeRequest req = StateCascadeRequest.defaults(maybe);
        CompletableFuture.runAsync(() -> runStateCascade(req), executor);
        return Map.of("queued", true, "profiles", req.profiles(), "states", req.states());
    }

    @PostMapping("/generate-unified-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> generateUnifiedEvents(@RequestBody(required = false) UnifiedEventRequest maybe) {
        UnifiedEventRequest req = UnifiedEventRequest.defaults(maybe);
        List<EventEnvelope> combined = new ArrayList<>();
        Instant now = Instant.now();
        List<String> statuses = req.statuses();
        List<String> channels = req.channels();
        List<String> regions = req.regions();
        List<String> tiers = req.tiers();
        Map<String, String> lastStatusByProfile = new HashMap<>();

        SampleRequest histogramRequest = new SampleRequest(
                req.serviceKey(),
                "http_request",
                "GET",
                "/api/checkout",
                List.of("200", "500"),
                "checkout",
                "v1",
                "demo",
                14,
                2000);
        List<EventEnvelope> histogramEvents = buildHistogramEvents(histogramRequest);

        int unifiedEventCount = req.events();
        int histogramIdx = 0;

        for (int i = 0; i < unifiedEventCount; i++) {
            String profileId = String.format("profile-%04d", (i % req.profilePool()) + 1);
            String previous = lastStatusByProfile.get(profileId);
            String status = pickNextStatus(statuses, previous, i);
            lastStatusByProfile.put(profileId, status);
            String channel = channels.get(i % channels.size());
            String region = regions.get(i % regions.size());
            String tier = tiers.get(i % tiers.size());
            long durationMs = Math.max(25L, random.nextInt(req.maxDurationMillis()));
            Instant start = now.minusSeconds(random.nextInt(req.recentWindowSeconds()));
            Instant end = start.plusMillis(durationMs);
            combined.add(buildUnifiedEvent(
                    req.serviceKey(),
                    req.eventType(),
                    profileId,
                    status,
                    tier,
                    channel,
                    region,
                    start,
                    end,
                    durationMs));

            if (!histogramEvents.isEmpty()) {
                combined.add(histogramEvents.get(histogramIdx));
                histogramIdx = (histogramIdx + 1) % histogramEvents.size();
            }
        }

        if (histogramIdx != 0 && histogramIdx < histogramEvents.size()) {
            combined.addAll(histogramEvents.subList(histogramIdx, histogramEvents.size()));
        }

        int stored = ingestService.ingestBatch(combined);
        triggerFlushes();
        Map<String, Object> histogramSeed = Map.of(
                "generated", histogramEvents.size(),
                "service", histogramRequest.serviceKey(),
                "eventType", histogramRequest.eventType());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generated", combined.size());
        response.put("stored", stored);
        response.put("service", req.serviceKey());
        response.put("eventType", req.eventType());
        response.put("histogramSeed", histogramSeed);
        return response;
    }

    private String selectStatus(List<String> statusCodes, int eventIndex) {
        if (statusCodes == null || statusCodes.isEmpty()) {
            return "200";
        }
        if (statusCodes.size() == 1) {
            return statusCodes.get(0);
        }
        String success = statusCodes.get(0);
        List<String> errors = statusCodes.subList(1, statusCodes.size());
        // default error frequency ~5%
        if (ERROR_FREQUENCY <= 0) {
            return success;
        }
        if (eventIndex % ERROR_FREQUENCY == 0) {
            int errorIndex = (eventIndex / ERROR_FREQUENCY) % errors.size();
            return errors.get(errorIndex);
        }
        return success;
    }

    private void runStateCascade(StateCascadeRequest request) {
        List<String> states = request.states();
        if (states == null || states.isEmpty()) {
            states = List.of("ACTIVE", "INACTIVE", "BLOCKED", "SUSPENDED", "ARCHIVED", "VERIFIED");
        }
        int profiles = Math.max(1, request.profiles());
        int transitionsPerProfile = Math.max(1, request.transitionsPerProfile());
        long eventsPerProfile = transitionsPerProfile + 1L;
        long totalMillis = request.totalDurationMinutes() * 60_000L;
        long delayMillis = Math.max(1L, totalMillis / Math.max(1L, profiles * eventsPerProfile));

        for (int i = 1; i <= profiles; i++) {
            String profileId = String.format("profile-%03d", i);
            String current = randomState(states, null);
            emitStateEvent(profileId, current);
            for (int t = 0; t < transitionsPerProfile; t++) {
                String next = randomState(states, current);
                emitStateEvent(profileId, next);
                current = next;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private String randomState(List<String> states, String exclude) {
        if (states.size() == 1) {
            return states.get(0);
        }
        String choice;
        do {
            choice = states.get(random.nextInt(states.size()));
        } while (exclude != null && choice.equals(exclude));
        return choice;
    }

    private void emitStateEvent(String profileId, String state) {
        ingestService.ingestOne(buildStateEvent(profileId, state));
    }

    private EventEnvelope buildStateEvent(String profileId, String state) {
        Instant now = Instant.now();
        return EventEnvelope.builder()
                .serviceId("payments")
                .eventType("user_profile.updated")
                .eventId(UUID.randomUUID().toString())
                .timestamp(now)
                .ingestedAt(now)
                .name("user_profile.updated")
                .attributes(Map.of(
                        "user",
                        Map.of(
                                "profile_id", profileId,
                                "email", profileId + "@example.com",
                                "status", state)))
                .resourceAttributes(Map.of())
                .build();
    }

    private EventEnvelope buildUnifiedEvent(
            String serviceKey,
            String eventType,
            String profileId,
            String status,
            String tier,
            String channel,
            String region,
            Instant start,
            Instant end,
            long durationMs) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(
                "user",
                Map.of(
                        "profile_id", profileId,
                        "status", status,
                        "tier", tier));
        attributes.put("timings", Map.of("duration_ms", durationMs));
        attributes.put(
                "dimensions",
                Map.of(
                        "channel", channel,
                        "region", region));
        return EventEnvelope.builder()
                .serviceId(serviceKey)
                .eventType(eventType)
                .eventId(UUID.randomUUID().toString())
                .timestamp(start)
                .endTimestamp(end)
                .ingestedAt(Instant.now())
                .name(eventType)
                .attributes(attributes)
                .resourceAttributes(Map.of())
                .build();
    }

    private String pickNextStatus(List<String> statuses, String previous, int seed) {
        if (statuses == null || statuses.isEmpty()) {
            return previous == null ? "ACTIVE" : previous;
        }
        if (statuses.size() == 1) {
            return statuses.get(0);
        }
        int index = Math.floorMod(seed, statuses.size());
        String candidate = statuses.get(index);
        if (previous == null || !previous.equals(candidate)) {
            return candidate;
        }
        return statuses.get((index + 1) % statuses.size());
    }

    private void triggerFlushes() {
        try {
            for (CounterGranularity granularity : CounterGranularity.values()) {
                counterFlushService.flushAndWait(granularity);
            }
            histogramFlushService.flushAndWait();
        } catch (Exception ex) {
            log.warn("Demo data flush failed", ex);
        }
    }

    private List<EventEnvelope> buildHistogramEvents(SampleRequest req) {
        Instant requestTime = Instant.now();
        Instant startOfCurrentDay = requestTime
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        List<EventEnvelope> events = new ArrayList<>();
        double[] latencyProfile = {50, 65, 90, 120, 160, 210, 280, 360, 450, 560, 700, 900, 1150, 1400};
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
                int eventIndex = (day * eventsPerDay) + slot;
                String status = selectStatus(req.statusCodes(), eventIndex);
                events.add(buildEvent(req, start, end, status));
            }
        }
        return events;
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

    public record StateCascadeRequest(
            Integer profiles, List<String> states, Long totalDurationMinutes, Integer transitionsPerProfile) {
        static StateCascadeRequest defaults(StateCascadeRequest maybe) {
            if (maybe == null) {
                return new StateCascadeRequest(250, List.of("ACTIVE", "INACTIVE", "BLOCKED", "SUSPENDED"), 10L, 3);
            }
            int prof = maybe.profiles == null || maybe.profiles <= 0 ? 250 : maybe.profiles;
            List<String> st = (maybe.states == null || maybe.states.isEmpty())
                    ? List.of("ACTIVE", "INACTIVE", "BLOCKED", "SUSPENDED")
                    : maybe.states;
            long duration = maybe.totalDurationMinutes == null || maybe.totalDurationMinutes <= 0
                    ? 10L
                    : maybe.totalDurationMinutes;
            int transitions = maybe.transitionsPerProfile == null || maybe.transitionsPerProfile <= 0
                    ? st.size()
                    : maybe.transitionsPerProfile;
            return new StateCascadeRequest(prof, st, duration, transitions);
        }
    }

    public record UnifiedEventRequest(
            String serviceKey,
            String eventType,
            Integer events,
            Integer profilePool,
            List<String> statuses,
            List<String> channels,
            List<String> regions,
            List<String> tiers,
            Integer maxDurationMillis,
            Integer recentWindowSeconds) {
        static UnifiedEventRequest defaults(UnifiedEventRequest maybe) {
            if (maybe == null) {
                return new UnifiedEventRequest(
                        "payments",
                        "user_profile.updated",
                        500,
                        50,
                        List.of("NEW", "ACTIVE", "SUSPENDED", "ACTIVE", "BLOCKED", "UPGRADED", "ARCHIVED"),
                        List.of("web", "mobile", "partner"),
                        List.of("us-east", "us-west", "eu-central"),
                        List.of("FREE", "PLUS", "PRO"),
                        1500,
                        3600);
            }
            return new UnifiedEventRequest(
                    emptyToDefault(maybe.serviceKey, "payments"),
                    emptyToDefault(maybe.eventType, "user_profile.updated"),
                    maybe.events == null || maybe.events <= 0 ? 500 : maybe.events,
                    maybe.profilePool == null || maybe.profilePool <= 0 ? 50 : maybe.profilePool,
                    (maybe.statuses == null || maybe.statuses.isEmpty())
                            ? List.of("NEW", "ACTIVE", "SUSPENDED", "ACTIVE", "BLOCKED", "UPGRADED", "ARCHIVED")
                            : maybe.statuses,
                    (maybe.channels == null || maybe.channels.isEmpty())
                            ? List.of("web", "mobile", "partner")
                            : maybe.channels,
                    (maybe.regions == null || maybe.regions.isEmpty())
                            ? List.of("us-east", "us-west", "eu-central")
                            : maybe.regions,
                    (maybe.tiers == null || maybe.tiers.isEmpty()) ? List.of("FREE", "PLUS", "PRO") : maybe.tiers,
                    maybe.maxDurationMillis == null || maybe.maxDurationMillis <= 0 ? 1500 : maybe.maxDurationMillis,
                    maybe.recentWindowSeconds == null || maybe.recentWindowSeconds <= 0
                            ? 3600
                            : maybe.recentWindowSeconds);
        }

        private static String emptyToDefault(String value, String fallback) {
            return (value == null || value.isBlank()) ? fallback : value;
        }
    }
}

package com.obsinity.reference.api;

import com.obsinity.reference.demodata.DemoProfileGeneratorProperties;
import com.obsinity.service.core.counter.DurationParser;
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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final DemoProfileGeneratorProperties profileGeneratorProperties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "demo-state-generator");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService demoExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "demo-unified-generator");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<UnifiedDemoRunner> unifiedDemoRunner = new AtomicReference<>();
    private final AtomicReference<UnifiedEventRequest> unifiedDemoConfig = new AtomicReference<>();
    private final AtomicReference<Integer> unifiedDemoIntervalSeconds = new AtomicReference<>();
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
        return startUnifiedEvents(maybe);
    }

    private Map<String, Object> startUnifiedEvents(UnifiedEventRequest maybe) {
        UnifiedEventRequest req = UnifiedEventRequest.defaults(maybe);
        int intervalSeconds = resolveRunIntervalSeconds(req);
        unifiedDemoConfig.set(req);
        unifiedDemoIntervalSeconds.set(intervalSeconds);
        if (maybe != null && maybe.hasProfileGeneratorOverrides()) {
            applyProfileGeneratorOverrides(maybe);
        }

        synchronized (profileGeneratorProperties) {
            profileGeneratorProperties.setEnabled(true);
        }

        UnifiedDemoRunner existing = unifiedDemoRunner.get();
        if (existing == null || existing.task().isDone()) {
            AtomicBoolean stopSignal = new AtomicBoolean(false);
            AtomicReference<Instant> lastRunAt = new AtomicReference<>();
            AtomicReference<Map<String, Object>> lastResult = new AtomicReference<>();
            Instant startedAt = Instant.now();
            Future<?> task = demoExecutor.submit(() -> runUnifiedLoop(stopSignal, lastRunAt, lastResult));
            UnifiedDemoRunner runner =
                    new UnifiedDemoRunner(req, intervalSeconds, startedAt, stopSignal, task, lastRunAt, lastResult);
            unifiedDemoRunner.set(runner);
        }
        return buildCombinedGeneratorStatus();
    }

    private Map<String, Object> applyProfileGeneratorOverrides(UnifiedEventRequest request) {
        synchronized (profileGeneratorProperties) {
            if (request.runEvery() != null && !request.runEvery().isBlank()) {
                profileGeneratorProperties.setRunEvery(DurationParser.parse(request.runEvery()));
            }
            if (request.targetCount() != null) {
                profileGeneratorProperties.setTargetCount(request.targetCount());
            }
            if (request.createPerRun() != null) {
                profileGeneratorProperties.setCreatePerRun(request.createPerRun());
            }
            if (request.oversampleFactor() != null) {
                profileGeneratorProperties.setOversampleFactor(request.oversampleFactor());
            }
            if (request.maxSelectionPerState() != null) {
                profileGeneratorProperties.setMaxSelectionPerState(request.maxSelectionPerState());
            }
            if (request.initialState() != null && !request.initialState().isBlank()) {
                profileGeneratorProperties.setInitialState(request.initialState());
            }
            profileGeneratorProperties.setEnabled(true);
        }
        return buildProfileGeneratorStatus();
    }

    @PostMapping("/generate-unified-events/stop")
    public Map<String, Object> stopUnifiedEvents() {
        UnifiedDemoRunner runner = unifiedDemoRunner.get();
        if (runner != null) {
            runner.stopSignal().set(true);
            runner.task().cancel(true);
        }
        synchronized (profileGeneratorProperties) {
            profileGeneratorProperties.setEnabled(false);
        }
        return buildCombinedGeneratorStatus();
    }

    @GetMapping("/generate-unified-events/status")
    public Map<String, Object> unifiedEventsStatus() {
        return buildCombinedGeneratorStatus();
    }

    private Map<String, Object> buildProfileGeneratorStatus() {
        Map<String, Object> response = new LinkedHashMap<>();
        synchronized (profileGeneratorProperties) {
            response.put("running", profileGeneratorProperties.isEnabled());
            response.put("mode", "db-driven");
            response.put("runEvery", profileGeneratorProperties.getRunEvery().toString());
            response.put("targetCount", profileGeneratorProperties.getTargetCount());
            response.put("targetCapped", profileGeneratorProperties.getTargetCount() > 0);
            response.put("createPerRun", profileGeneratorProperties.getCreatePerRun());
            response.put("oversampleFactor", profileGeneratorProperties.getOversampleFactor());
            response.put("maxSelectionPerState", profileGeneratorProperties.getMaxSelectionPerState());
            response.put("initialState", profileGeneratorProperties.getInitialState());
            response.put("transitions", profileGeneratorProperties.getTransitions());
        }
        return response;
    }

    private Map<String, Object> buildCombinedGeneratorStatus() {
        Map<String, Object> response = new LinkedHashMap<>(buildProfileGeneratorStatus());
        UnifiedDemoRunner runner = unifiedDemoRunner.get();
        boolean httpRunnerRunning = runner != null && !runner.task().isDone();
        response.put("httpDemoRunnerRunning", httpRunnerRunning);
        if (runner != null) {
            response.put("httpDemoRunnerStartedAt", runner.startedAt().toString());
            Instant lastRun = runner.lastRunAt().get();
            if (lastRun != null) {
                response.put("httpDemoLastRunAt", lastRun.toString());
            }
            Map<String, Object> lastResult = runner.lastResult().get();
            if (lastResult != null) {
                response.put("httpDemoLastResult", lastResult);
            }
        }
        return response;
    }

    private Map<String, Object> generateUnifiedEventsOnce(UnifiedEventRequest req) {
        Instant now = Instant.now();
        List<String> statuses = req.statuses();
        List<String> channels = req.channels();
        List<String> regions = req.regions();
        List<String> tiers = req.tiers();
        Map<String, String> lastStatusByProfile = new HashMap<>();
        boolean emitProfileEvents;
        synchronized (profileGeneratorProperties) {
            emitProfileEvents = !profileGeneratorProperties.isEnabled();
        }

        int unifiedEventCount = resolveUnifiedEventCount(req);
        int profiles = Math.max(1, req.profilePool());
        // Keep profile ids stable across runs so state transitions are not dominated by "(none) -> X".
        String runPrefix = req.serviceKey().replaceAll("[^a-zA-Z0-9_-]", "_");
        SampleRequest httpSample = SampleRequest.defaults(null);

        if (emitProfileEvents) {
            // Seed random initial state per profile
            for (int profileIndex = 0; profileIndex < profiles; profileIndex++) {
                String profileId = String.format("%s-profile-%04d", runPrefix, profileIndex + 1);
                String seedStatus = pickRandomStatus(statuses);
                lastStatusByProfile.put(profileId, seedStatus);
                long durationMs = 25L + random.nextInt(Math.max(1, req.maxEventDurationMillis()));
                Instant seedStart = Instant.now();
                Instant seedEnd = seedStart.plusMillis(durationMs);
                ingestService.ingestOne(buildUnifiedEvent(
                        req.serviceKey(),
                        req.eventType(),
                        profileId,
                        seedStatus,
                        pickRandomValue(tiers, "FREE"),
                        pickRandomValue(channels, "web"),
                        pickRandomValue(regions, "us-east"),
                        seedStart,
                        seedEnd,
                        durationMs));
                ingestService.ingestOne(buildEvent(httpSample, seedStart, seedEnd, "200"));
            }
        }

        long remaining = emitProfileEvents ? Math.max(0, unifiedEventCount - profiles) : 0L;
        if (emitProfileEvents && remaining > 0) {
            long emitted = 0;
            while (emitted < remaining) {
                String profileId = String.format("%s-profile-%04d", runPrefix, (int) (emitted % profiles) + 1);
                String status = pickRandomStatus(statuses);
                lastStatusByProfile.put(profileId, status);
                String channel = pickRandomValue(channels, "web");
                String region = pickRandomValue(regions, "us-east");
                String tier = pickRandomValue(tiers, "FREE");
                long durationMs = 25L + random.nextInt(Math.max(1, req.maxEventDurationMillis()));
                Instant start = Instant.now();
                Instant end = start.plusMillis(durationMs);
                ingestService.ingestOne(buildUnifiedEvent(
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
                emitted++;
                ingestService.ingestOne(buildEvent(httpSample, start, end, "200"));
            }
        }

        ingestHistogramEvents(req, unifiedEventCount);

        int generated = emitProfileEvents ? unifiedEventCount + profiles : unifiedEventCount;
        return Map.of(
                "generated",
                generated,
                "stored",
                generated,
                "service",
                req.serviceKey(),
                "eventType",
                req.eventType(),
                "profileEventsEnabled",
                emitProfileEvents,
                "histogramSeed",
                Map.of("generated", unifiedEventCount, "service", req.serviceKey(), "eventType", "http_request"));
    }

    private String pickRandomValue(List<String> values, String fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        return values.get(random.nextInt(values.size()));
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

    private String pickRandomStateDifferent(String previous, List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return "ACTIVE";
        }
        if (statuses.size() == 1) {
            return statuses.get(0);
        }
        String next;
        do {
            next = statuses.get(random.nextInt(statuses.size()));
        } while (next.equals(previous));
        return next;
    }

    private String pickRandomStatus(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return "ACTIVE";
        }
        return statuses.get(random.nextInt(statuses.size()));
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
        long durationMillis = Math.max(1L, Duration.between(start, end).toMillis());
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("environment", req.environment());

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", req.httpMethod());
        http.put("route", req.httpRoute());
        http.put("status", toHttpStatus(status));
        http.put("server", Map.of("duration_ms", durationMillis));

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

    private int ingestHistogramEvents(UnifiedEventRequest req, int sampleCount) {
        double[] latencyProfile = {50, 65, 90, 120, 160, 210, 280, 360, 450, 560, 700, 900, 1150, 1400};
        List<String> statusCodes = List.of("200", "500");
        int stored = 0;
        long runSeed = Instant.now().getEpochSecond();
        boolean degradedWindow = (runSeed / 30L) % 3L == 1L;
        for (int i = 0; i < sampleCount; i++) {
            long sequence = runSeed + i;
            double baseLatency = latencyProfile[(int) (Math.floorMod(sequence, latencyProfile.length))];
            boolean spike = sequence % 37 == 0;
            if (spike) {
                baseLatency = 1400 + (sequence % 12) * 120;
            }
            if (degradedWindow) {
                baseLatency *= 1.35;
            }
            double jitterFactor = 0.8 + (random.nextDouble() * 0.7);
            long latencyMillis = Math.max(25, Math.round(baseLatency * jitterFactor));
            Instant start = Instant.now();
            Instant end = start.plusMillis(latencyMillis);
            String status = selectStatus(statusCodes, i);
            String route = (sequence % 3L == 0L) ? "/api/profile" : "/api/checkout";
            stored += ingestService.ingestOne(buildHistogramEvent(req.serviceKey(), route, start, end, status));
        }
        return stored;
    }

    private EventEnvelope buildHistogramEvent(
            String serviceKey, String route, Instant start, Instant end, String status) {
        long durationMillis = Math.max(1L, Duration.between(start, end).toMillis());
        Map<String, Object> resource = Map.of("environment", "demo");
        Map<String, Object> http = Map.of(
                "method",
                "GET",
                "route",
                route,
                "status",
                toHttpStatus(status),
                "server",
                Map.of("duration_ms", durationMillis));
        Map<String, Object> api = Map.of("name", "checkout", "version", "v1");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("http", http);
        attributes.put("api", api);

        return EventEnvelope.builder()
                .serviceId(serviceKey)
                .eventType("http_request")
                .eventId(UUID.randomUUID().toString())
                .timestamp(start)
                .endTimestamp(end)
                .ingestedAt(Instant.now())
                .name("http_request")
                .kind("SERVER")
                .resourceAttributes(resource)
                .attributes(attributes)
                .build();
    }

    private int toHttpStatus(String status) {
        try {
            return Integer.parseInt(status);
        } catch (Exception ignored) {
            return 200;
        }
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
            String duration,
            Integer eventsPerSecond,
            Integer events,
            Integer profilePool,
            List<String> statuses,
            List<String> channels,
            List<String> regions,
            List<String> tiers,
            Integer maxEventDurationMillis,
            String recentWindow,
            Long recentWindowSeconds,
            Integer runIntervalSeconds,
            String runEvery,
            Integer targetCount,
            Integer createPerRun,
            Integer oversampleFactor,
            Integer maxSelectionPerState,
            String initialState) {
        static UnifiedEventRequest defaults(UnifiedEventRequest maybe) {
            if (maybe == null) {
                return new UnifiedEventRequest(
                        "payments",
                        "user_profile.updated",
                        "5m",
                        1000,
                        null,
                        50,
                        List.of(
                                "NEW",
                                "ACTIVE",
                                "ACTIVE",
                                "ACTIVE",
                                "SUSPENDED",
                                "SUSPENDED",
                                "BLOCKED",
                                "UPGRADED",
                                "ARCHIVED",
                                "ARCHIVED",
                                "ARCHIVED"),
                        List.of("web", "mobile", "partner"),
                        List.of("us-east", "us-west", "eu-central"),
                        List.of("FREE", "PLUS", "PRO"),
                        1500,
                        "1s",
                        null,
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            }
            return new UnifiedEventRequest(
                    emptyToDefault(maybe.serviceKey, "payments"),
                    emptyToDefault(maybe.eventType, "user_profile.updated"),
                    emptyToDefault(maybe.duration, "5m"),
                    maybe.eventsPerSecond == null || maybe.eventsPerSecond <= 0 ? 1000 : maybe.eventsPerSecond,
                    maybe.events == null || maybe.events <= 0 ? null : maybe.events,
                    maybe.profilePool == null || maybe.profilePool <= 0 ? 50 : maybe.profilePool,
                    (maybe.statuses == null || maybe.statuses.isEmpty())
                            ? List.of(
                                    "NEW",
                                    "ACTIVE",
                                    "ACTIVE",
                                    "ACTIVE",
                                    "SUSPENDED",
                                    "SUSPENDED",
                                    "BLOCKED",
                                    "UPGRADED",
                                    "ARCHIVED",
                                    "ARCHIVED",
                                    "ARCHIVED")
                            : maybe.statuses,
                    (maybe.channels == null || maybe.channels.isEmpty())
                            ? List.of("web", "mobile", "partner")
                            : maybe.channels,
                    (maybe.regions == null || maybe.regions.isEmpty())
                            ? List.of("us-east", "us-west", "eu-central")
                            : maybe.regions,
                    (maybe.tiers == null || maybe.tiers.isEmpty()) ? List.of("FREE", "PLUS", "PRO") : maybe.tiers,
                    maybe.maxEventDurationMillis == null || maybe.maxEventDurationMillis <= 0
                            ? 1500
                            : maybe.maxEventDurationMillis,
                    emptyToDefault(maybe.recentWindow, "1s"),
                    maybe.recentWindowSeconds == null || maybe.recentWindowSeconds <= 0
                            ? null
                            : maybe.recentWindowSeconds,
                    maybe.runIntervalSeconds == null || maybe.runIntervalSeconds <= 0 ? 1 : maybe.runIntervalSeconds,
                    maybe.runEvery,
                    maybe.targetCount,
                    maybe.createPerRun,
                    maybe.oversampleFactor,
                    maybe.maxSelectionPerState,
                    maybe.initialState);
        }

        boolean hasProfileGeneratorOverrides() {
            return runEvery != null
                    || targetCount != null
                    || createPerRun != null
                    || oversampleFactor != null
                    || maxSelectionPerState != null
                    || initialState != null;
        }

        boolean hasLegacyGeneratorOverrides() {
            return serviceKey != null
                    || eventType != null
                    || duration != null
                    || eventsPerSecond != null
                    || events != null
                    || profilePool != null
                    || statuses != null
                    || channels != null
                    || regions != null
                    || tiers != null
                    || maxEventDurationMillis != null
                    || recentWindow != null
                    || recentWindowSeconds != null
                    || runIntervalSeconds != null;
        }

        private static String emptyToDefault(String value, String fallback) {
            return (value == null || value.isBlank()) ? fallback : value;
        }
    }

    private int resolveUnifiedEventCount(UnifiedEventRequest req) {
        if (req.events() != null && req.events() > 0) {
            return req.events();
        }
        Duration duration = DurationParser.parse(req.duration());
        long durationSeconds = Math.max(1L, duration.getSeconds());
        int eventsPerSecond = Math.max(1, req.eventsPerSecond());
        long total = durationSeconds * (long) eventsPerSecond;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, total));
    }

    private long resolveRecentWindowSeconds(UnifiedEventRequest req) {
        if (req.recentWindowSeconds() != null && req.recentWindowSeconds() > 0) {
            return req.recentWindowSeconds();
        }
        Duration windowDuration = DurationParser.parse(req.recentWindow());
        return Math.max(1L, windowDuration.getSeconds());
    }

    private int resolveRunIntervalSeconds(UnifiedEventRequest req) {
        if (req.runIntervalSeconds() != null && req.runIntervalSeconds() > 0) {
            return req.runIntervalSeconds();
        }
        long windowSeconds = resolveRecentWindowSeconds(req);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(5L, windowSeconds / 6L));
    }

    private void runUnifiedLoop(
            AtomicBoolean stopSignal,
            AtomicReference<Instant> lastRunAt,
            AtomicReference<Map<String, Object>> lastResult) {
        while (!stopSignal.get()) {
            UnifiedEventRequest req = unifiedDemoConfig.get();
            if (req == null) {
                req = UnifiedEventRequest.defaults(null);
            }
            Integer configuredInterval = unifiedDemoIntervalSeconds.get();
            int intervalSeconds = configuredInterval != null && configuredInterval > 0
                    ? configuredInterval
                    : resolveRunIntervalSeconds(req);
            lastRunAt.set(Instant.now());
            lastResult.set(generateUnifiedEventsOnce(req));
            try {
                Thread.sleep(Math.max(1L, intervalSeconds) * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Map<String, Object> buildUnifiedRunnerStatus(UnifiedDemoRunner runner, boolean running) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("running", running);
        Integer interval = unifiedDemoIntervalSeconds.get();
        response.put("intervalSeconds", interval != null ? interval : runner.intervalSeconds());
        response.put("startedAt", runner.startedAt().toString());
        Instant lastRun = runner.lastRunAt().get();
        if (lastRun != null) {
            response.put("lastRunAt", lastRun.toString());
        }
        Map<String, Object> lastResult = runner.lastResult().get();
        if (lastResult != null) {
            response.put("lastResult", lastResult);
        }
        UnifiedEventRequest current = unifiedDemoConfig.get();
        response.put("request", current != null ? current : runner.request());
        return response;
    }

    private record UnifiedDemoRunner(
            UnifiedEventRequest request,
            int intervalSeconds,
            Instant startedAt,
            AtomicBoolean stopSignal,
            Future<?> task,
            AtomicReference<Instant> lastRunAt,
            AtomicReference<Map<String, Object>> lastResult) {}
}

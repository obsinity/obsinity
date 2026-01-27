package com.obsinity.e2e;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class ApiE2EJUnitTest {
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Rule
    public TestName testName = new TestName();

    private final OkHttpClient client = new OkHttpClient();
    private final String baseUrl =
            Optional.ofNullable(System.getenv("OBSINITY_BASE_URL")).orElse("http://localhost:8086");
    private final String runId = Optional.ofNullable(System.getenv("OBSINITY_RUN_ID"))
            .orElse(UUID.randomUUID().toString());
    private final Instant suiteStart = Instant.now().minusSeconds(600);
    private final File failuresDir = new File("artifacts/" + runId + "/failures");
    private final List<Map<String, Object>> interactions = new ArrayList<>();
    private final List<Map<String, Object>> pollAttempts = new ArrayList<>();

    @Before
    public void logTestStart() {
        System.out.println("TEST START: " + testName.getMethodName());
    }

    @Test
    public void immediateTransitionCounts_simple() throws Exception {
        Instant base = testBaseTime(0);
        publishStatus(profileId("simple"), "NEW", base);
        publishStatus(profileId("simple"), "ACTIVE", base.plusSeconds(1));

        Map<String, Long> expected = Map.of("NEW->ACTIVE", 1L);
        Map<String, Long> counts =
                pollTransitions(base, base.plusSeconds(5), List.of("NEW"), List.of("ACTIVE"), expected);
        assertCounts("immediateTransitionCounts_simple", counts, expected);
    }

    @Test
    public void immediateTransitionCounts_backAndForth() throws Exception {
        Instant base = testBaseTime(1);
        String id = profileId("backforth");
        publishStatus(id, "NEW", base);
        publishStatus(id, "ACTIVE", base.plusSeconds(1));
        publishStatus(id, "NEW", base.plusSeconds(2));
        publishStatus(id, "ACTIVE", base.plusSeconds(3));

        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("NEW->ACTIVE", 2L);
        expected.put("ACTIVE->NEW", 1L);
        Map<String, Long> counts = pollTransitions(
                base, base.plusSeconds(6), List.of("NEW", "ACTIVE"), List.of("ACTIVE", "NEW"), expected);
        assertCounts("immediateTransitionCounts_backAndForth", counts, expected);
    }

    @Test
    public void configuredTransition_countsWithIntermediates() throws Exception {
        Instant base = testBaseTime(2);
        String id = profileId("intermediate");
        publishStatus(id, "NEW", base);
        publishStatus(id, "ACTIVE", base.plusSeconds(1));
        publishStatus(id, "ARCHIVED", base.plusSeconds(2));

        Map<String, Long> expected = Map.of("NEW->ARCHIVED", 1L);
        Map<String, Long> counts = pollRatios(
                base,
                base.plusSeconds(6),
                List.of(Map.of("from", List.of("NEW"), "to", List.of("ARCHIVED"))),
                expected);
        assertCounts("configuredTransition_countsWithIntermediates", counts, expected);
    }

    @Test
    public void configuredTransition_countsDespiteBacktracking() throws Exception {
        Instant base = testBaseTime(3);
        String id = profileId("backtrack");
        publishStatus(id, "NEW", base);
        publishStatus(id, "ACTIVE", base.plusSeconds(1));
        publishStatus(id, "NEW", base.plusSeconds(2));
        publishStatus(id, "ARCHIVED", base.plusSeconds(3));

        Map<String, Long> expected = Map.of("NEW->ARCHIVED", 1L);
        Map<String, Long> counts = pollRatios(
                base,
                base.plusSeconds(7),
                List.of(Map.of("from", List.of("NEW"), "to", List.of("ARCHIVED"))),
                expected);
        assertCounts("configuredTransition_countsDespiteBacktracking", counts, expected);
    }

    @Test
    public void ratio_finishedVsAbandoned_usesConfiguredTransitions() throws Exception {
        Instant base = testBaseTime(4);
        publishStatus(profileId("finish"), "NEW", base);
        publishStatus(profileId("finish"), "ARCHIVED", base.plusSeconds(1));
        publishStatus(profileId("abandon"), "NEW", base.plusSeconds(2));
        publishStatus(profileId("abandon"), "BLOCKED", base.plusSeconds(3));

        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("NEW->ARCHIVED", 1L);
        expected.put("NEW->BLOCKED", 1L);
        Map<String, Long> counts = pollRatios(
                base,
                base.plusSeconds(8),
                List.of(Map.of("from", List.of("NEW"), "to", List.of("ARCHIVED", "BLOCKED"))),
                expected);
        assertCounts("ratio_finishedVsAbandoned_usesConfiguredTransitions", counts, expected);

        Map<String, RatioEntry> stats = queryTransitionRatioStats(
                base,
                base.plusSeconds(8),
                List.of(Map.of("from", List.of("NEW"), "to", List.of("ARCHIVED", "BLOCKED"))));
        assertRatio(
                "ratio_finishedVsAbandoned_usesConfiguredTransitions",
                stats,
                Map.of("NEW->ARCHIVED", 0.5d, "NEW->BLOCKED", 0.5d));
    }

    @Test
    public void ratio_zeroDenominator() throws Exception {
        Instant base = suiteStart.minusSeconds(3600);
        Map<String, Long> expected = Map.of("NEW->ARCHIVED", 0L, "NEW->BLOCKED", 0L);
        Map<String, Long> counts = pollRatios(
                base,
                base.plusSeconds(10),
                List.of(Map.of("from", List.of("NEW"), "to", List.of("ARCHIVED", "BLOCKED"))),
                expected);
        assertCounts("ratio_zeroDenominator", counts, expected);

        Map<String, RatioEntry> stats = queryTransitionRatioStats(
                base,
                base.plusSeconds(10),
                List.of(Map.of("from", List.of("NEW"), "to", List.of("ARCHIVED", "BLOCKED"))));
        assertRatio("ratio_zeroDenominator", stats, Map.of("NEW->ARCHIVED", 0.0d, "NEW->BLOCKED", 0.0d));
    }

    private String profileId(String suffix) {
        return runId + "-" + suffix;
    }

    private Instant testBaseTime(int index) {
        return suiteStart.plusSeconds(index * 120L);
    }

    private void publishStatus(String profileId, String status, Instant ts) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("resource", Map.of("service", Map.of("name", "payments")));
        payload.put("event", Map.of("name", "user_profile.updated"));
        payload.put(
                "time",
                Map.of("startedAt", ts.toString(), "endedAt", ts.plusMillis(10).toString()));
        payload.put(
                "attributes",
                Map.of(
                        "user", Map.of("profile_id", profileId, "status", status),
                        "obsinity", Map.of("run_id", runId)));
        Map<String, Object> response = postJson("/events/publish", payload);
        assertEquals(202, response.get("status"));
    }

    private Map<String, Long> pollTransitions(
            Instant start, Instant end, List<String> fromStates, List<String> toStates, Map<String, Long> expected)
            throws Exception {
        return poll("state-transitions", Duration.ofSeconds(30), Duration.ofSeconds(2), () -> {
            Map<String, Long> counts = queryStateTransitions(start, end, fromStates, toStates);
            return matchesExpected(counts, expected) ? counts : null;
        });
    }

    private Map<String, Long> pollRatios(
            Instant start, Instant end, List<Map<String, Object>> transitions, Map<String, Long> expected)
            throws Exception {
        return poll("transition-ratios", Duration.ofSeconds(30), Duration.ofSeconds(2), () -> {
            Map<String, Long> counts = queryTransitionRatios(start, end, transitions);
            return matchesExpected(counts, expected) ? counts : null;
        });
    }

    private Map<String, Long> queryStateTransitions(
            Instant start, Instant end, List<String> fromStates, List<String> toStates) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serviceKey", "payments");
        payload.put("objectType", "UserProfile");
        payload.put("attribute", "user.status");
        payload.put("fromStates", fromStates);
        payload.put("toStates", toStates);
        payload.put("interval", "5s");
        payload.put("start", start.toString());
        payload.put("end", end.toString());
        payload.put("limits", Map.of("offset", 0, "limit", 20));
        Map<String, Object> response = postJson("/api/query/state-transitions", payload);
        assertEquals(200, response.get("status"));
        return extractTransitionCounts(response.get("body"));
    }

    private Map<String, Long> queryTransitionRatios(Instant start, Instant end, List<Map<String, Object>> transitions)
            throws Exception {
        Instant alignedStart = alignDownToBucket(start, 5);
        Instant alignedEnd = alignUpToBucket(end, 5);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serviceKey", "payments");
        payload.put("objectType", "UserProfile");
        payload.put("attribute", "user.status");
        payload.put("transitions", transitions);
        payload.put("interval", "5s");
        payload.put("start", alignedStart.toString());
        payload.put("end", alignedEnd.toString());
        payload.put("groupByFromState", true);
        Map<String, Object> response = postJson("/api/query/transition-ratios", payload);
        assertEquals(200, response.get("status"));
        return extractRatioCounts(response.get("body"));
    }

    private Map<String, RatioEntry> queryTransitionRatioStats(
            Instant start, Instant end, List<Map<String, Object>> transitions) throws Exception {
        Instant alignedStart = alignDownToBucket(start, 5);
        Instant alignedEnd = alignUpToBucket(end, 5);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serviceKey", "payments");
        payload.put("objectType", "UserProfile");
        payload.put("attribute", "user.status");
        payload.put("transitions", transitions);
        payload.put("interval", "5s");
        payload.put("start", alignedStart.toString());
        payload.put("end", alignedEnd.toString());
        payload.put("groupByFromState", true);
        Map<String, Object> response = postJson("/api/query/transition-ratios", payload);
        assertEquals(200, response.get("status"));
        return extractRatioStats(response.get("body"));
    }

    private Map<String, Object> postJson(String path, Object payload) throws Exception {
        String url = baseUrl + path;
        String body = MAPPER.writeValueAsString(payload);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON))
                .build();

        long startedAt = System.currentTimeMillis();
        Response response = client.newCall(request).execute();
        long finishedAt = System.currentTimeMillis();
        String responseBody = response.body() != null ? response.body().string() : "";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", response.code());
        result.put("body", parseJsonOrText(responseBody));

        recordInteraction("POST", url, payload, result, finishedAt - startedAt);
        return result;
    }

    private Object parseJsonOrText(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return MAPPER.readValue(body, Object.class);
        } catch (Exception ignored) {
            return body;
        }
    }

    private Map<String, Long> extractTransitionCounts(Object body) {
        if (!(body instanceof Map)) {
            return Map.of();
        }
        Map<String, Object> map = (Map<String, Object>) body;
        Map<String, Object> data = (Map<String, Object>) map.get("data");
        List<Object> intervals = (List<Object>) data.get("intervals");
        Map<String, Long> counts = new LinkedHashMap<>();
        if (intervals != null) {
            for (Object interval : intervals) {
                Map<String, Object> intervalMap = (Map<String, Object>) interval;
                List<Object> transitions = (List<Object>) intervalMap.get("transitions");
                if (transitions == null) continue;
                for (Object entry : transitions) {
                    Map<String, Object> e = (Map<String, Object>) entry;
                    String key = e.get("fromState") + "->" + e.get("toState");
                    long count = ((Number) e.get("count")).longValue();
                    counts.put(key, counts.getOrDefault(key, 0L) + count);
                }
            }
        }
        return counts;
    }

    private Map<String, Long> extractRatioCounts(Object body) {
        Map<String, RatioEntry> stats = extractRatioStats(body);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<String, RatioEntry> entry : stats.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().count);
        }
        return counts;
    }

    private Map<String, RatioEntry> extractRatioStats(Object body) {
        if (!(body instanceof Map)) {
            return Map.of();
        }
        Map<String, Object> map = (Map<String, Object>) body;
        Map<String, Object> data = (Map<String, Object>) map.get("data");
        List<Object> transitions = (List<Object>) data.get("transitions");
        Map<String, RatioEntry> stats = new LinkedHashMap<>();
        if (transitions != null) {
            for (Object entry : transitions) {
                Map<String, Object> e = (Map<String, Object>) entry;
                String key = e.get("fromState") + "->" + e.get("toState");
                long count = ((Number) e.get("count")).longValue();
                double ratio = e.get("ratio") instanceof Number ? ((Number) e.get("ratio")).doubleValue() : 0.0d;
                stats.put(key, new RatioEntry(count, ratio));
            }
        }
        return stats;
    }

    private boolean matchesExpected(Map<String, Long> actual, Map<String, Long> expected) {
        for (Map.Entry<String, Long> entry : expected.entrySet()) {
            long actualVal = actual.getOrDefault(entry.getKey(), 0L);
            if (actualVal != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void assertCounts(String testName, Map<String, Long> actual, Map<String, Long> expected) throws Exception {
        if (!matchesExpected(actual, expected)) {
            writeFailure(testName, "Counts did not match expected values", expected, actual);
            assertEquals(expected, actual);
        }
    }

    private void assertRatio(String testName, Map<String, RatioEntry> actual, Map<String, Double> expected)
            throws Exception {
        boolean ok = true;
        for (Map.Entry<String, Double> entry : expected.entrySet()) {
            RatioEntry actualEntry = actual.get(entry.getKey());
            if (actualEntry == null || Math.abs(actualEntry.ratio - entry.getValue()) > 0.0001d) {
                ok = false;
            }
        }
        if (!ok) {
            writeFailure(testName, "Ratios did not match expected values", expected, actual);
            assertTrue("Ratios did not match expected values", false);
        }
    }

    private Map<String, Long> poll(
            String name, Duration timeout, Duration interval, SupplierWithException<Map<String, Long>> supplier)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        int attempt = 0;
        Map<String, Long> last = null;
        while (Instant.now().isBefore(deadline)) {
            attempt++;
            try {
                last = supplier.get();
                recordPollAttempt(name, attempt, last, null);
                if (last != null) {
                    return last;
                }
            } catch (Exception ex) {
                recordPollAttempt(name, attempt, null, ex.getMessage());
            }
            Thread.sleep(interval.toMillis());
        }
        return last != null ? last : new LinkedHashMap<>();
    }

    private void recordInteraction(String method, String url, Object request, Object response, long durationMs) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("method", method);
        entry.put("url", url);
        entry.put("request", request);
        entry.put("response", response);
        entry.put("durationMs", durationMs);
        entry.put("timestamp", Instant.now().toString());
        interactions.add(entry);
    }

    private void recordPollAttempt(String name, int attempt, Object result, String error) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("attempt", attempt);
        entry.put("timestamp", Instant.now().toString());
        if (error != null) {
            entry.put("error", error);
        } else {
            entry.put("result", result);
        }
        pollAttempts.add(entry);
    }

    private void writeFailure(String testName, String message, Object expected, Object actual) throws Exception {
        failuresDir.mkdirs();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("expected", expected);
        payload.put("actual", actual);
        payload.put("interactions", interactions);
        payload.put("pollAttempts", pollAttempts);
        Path out = new File(failuresDir, sanitize(testName) + ".json").toPath();
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
    }

    private String sanitize(String raw) {
        return raw.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private Instant alignDownToBucket(Instant ts, long bucketSeconds) {
        long epochSeconds = ts.getEpochSecond();
        long aligned = epochSeconds - (epochSeconds % bucketSeconds);
        return Instant.ofEpochSecond(aligned);
    }

    private Instant alignUpToBucket(Instant ts, long bucketSeconds) {
        long epochSeconds = ts.getEpochSecond();
        long mod = epochSeconds % bucketSeconds;
        long aligned = mod == 0 ? epochSeconds : epochSeconds + (bucketSeconds - mod);
        return Instant.ofEpochSecond(aligned);
    }

    private record RatioEntry(long count, double ratio) {}

    private interface SupplierWithException<T> {
        T get() throws Exception;
    }
}

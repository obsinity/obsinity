package com.obsinity.controller.rest.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.obsinity.controller.rest.grafana.GrafanaQueryModels.*;
import com.obsinity.service.core.api.ResponseFormat;
import com.obsinity.service.core.counter.CounterQueryRequest;
import com.obsinity.service.core.counter.CounterQueryResult;
import com.obsinity.service.core.counter.CounterQueryService;
import com.obsinity.service.core.counter.CounterQueryWindow;
import com.obsinity.service.core.counter.DurationParser;
import com.obsinity.service.core.histogram.HistogramQueryRequest;
import com.obsinity.service.core.histogram.HistogramQueryResult;
import com.obsinity.service.core.histogram.HistogramQueryService;
import com.obsinity.service.core.histogram.HistogramQueryWindow;
import com.obsinity.service.core.state.query.AdHocRatioQueryRequest;
import com.obsinity.service.core.state.query.RatioQueryRequest;
import com.obsinity.service.core.state.query.RatioQueryResult;
import com.obsinity.service.core.state.query.RatioQueryService;
import com.obsinity.service.core.state.query.StateCountQueryRequest;
import com.obsinity.service.core.state.query.StateCountQueryResult;
import com.obsinity.service.core.state.query.StateCountQueryService;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryRequest;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryResult;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryService;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/grafana", produces = MediaType.APPLICATION_JSON_VALUE)
public class GrafanaQueryController {

    private static final String KIND_HISTOGRAM = "histogram_percentiles";
    private static final String KIND_STATE_COUNT = "state_count";
    private static final String KIND_STATE_COUNT_SNAPSHOT = "state_count_snapshot";
    private static final String KIND_EVENT_COUNT = "event_count";
    private static final String FORMAT_TABLE = "table";
    private static final int DEFAULT_TIMESERIES_POINT_CAP = 1440;
    private static final Duration STATE_TIMESERIES_STEP = Duration.ofMinutes(1);
    private static final Duration TRANSITION_TIMESERIES_STEP = Duration.ofSeconds(5);
    private static final List<Duration> COUNTER_BUCKETS = List.of(
            Duration.ofSeconds(5),
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
            Duration.ofDays(1),
            Duration.ofDays(7));
    private final HistogramQueryService histogramQueryService;
    private final StateCountTimeseriesQueryService stateCountTimeseriesQueryService;
    private final StateCountQueryService stateCountQueryService;
    private final CounterQueryService counterQueryService;
    private final com.obsinity.service.core.state.query.StateTransitionQueryService stateTransitionQueryService;
    private final RatioQueryService ratioQueryService;
    private final ObjectMapper objectMapper;
    private final int timeseriesPointCap;

    public GrafanaQueryController(
            HistogramQueryService histogramQueryService,
            StateCountTimeseriesQueryService stateCountTimeseriesQueryService,
            StateCountQueryService stateCountQueryService,
            CounterQueryService counterQueryService,
            com.obsinity.service.core.state.query.StateTransitionQueryService stateTransitionQueryService,
            RatioQueryService ratioQueryService,
            ObjectMapper objectMapper,
            @Value("${obsinity.grafana.timeseries.max-data-points-cap:1440}") int timeseriesPointCap) {
        this.histogramQueryService = histogramQueryService;
        this.stateCountTimeseriesQueryService = stateCountTimeseriesQueryService;
        this.stateCountQueryService = stateCountQueryService;
        this.counterQueryService = counterQueryService;
        this.stateTransitionQueryService = stateTransitionQueryService;
        this.ratioQueryService = ratioQueryService;
        this.objectMapper = objectMapper;
        this.timeseriesPointCap = timeseriesPointCap > 0 ? timeseriesPointCap : DEFAULT_TIMESERIES_POINT_CAP;
    }

    @PostMapping(path = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public GrafanaQueryResponse query(@RequestBody JsonNode requestBody) {
        GrafanaQueryRequest request = parseRequest(requestBody);
        GrafanaRangeResolver.ResolvedRange range = GrafanaRangeResolver.resolve(request.range());
        Map<String, GrafanaResult> results = new LinkedHashMap<>();
        List<Map<String, Object>> rows = null;

        if (request.queries() == null) {
            return new GrafanaQueryResponse(Map.of(), null);
        }

        for (GrafanaSubQuery query : request.queries()) {
            if (query == null) {
                continue;
            }
            String refId = query.refId();
            String kind = query.kind();
            if (KIND_HISTOGRAM.equals(kind)) {
                GrafanaResult result = runHistogram(range, request, query);
                results.put(refId, result);
                if (rows == null && result.rows() != null) {
                    rows = result.rows();
                }
            } else if (KIND_STATE_COUNT.equals(kind)) {
                results.put(refId, runStateCount(range, request, query));
            } else if (KIND_STATE_COUNT_SNAPSHOT.equals(kind)) {
                GrafanaResult result = runStateCountSnapshot(request, query);
                results.put(refId, result);
                if (rows == null && result.rows() != null) {
                    rows = result.rows();
                }
            } else if (KIND_EVENT_COUNT.equals(kind)) {
                results.put(refId, runEventCount(range, request, query));
            } else {
                results.put(refId, new GrafanaResult(List.of(), null));
            }
        }

        return new GrafanaQueryResponse(results, rows);
    }

    @PostMapping(path = "/histograms", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> histogram(@RequestBody JsonNode requestBody) {
        SingleQuery query = parseSingleQuery(requestBody, KIND_HISTOGRAM);
        return rowsOnly(runHistogram(query.range(), query.request(), query.query()));
    }

    @PostMapping(path = "/state-counts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> stateCounts(@RequestBody JsonNode requestBody) {
        SingleQuery query = parseSingleQuery(requestBody, KIND_STATE_COUNT_SNAPSHOT);
        return rowsOnly(runStateCountSnapshot(query.request(), query.query()));
    }

    @PostMapping(path = "/state-count-timeseries", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> stateCountTimeseries(@RequestBody JsonNode requestBody) {
        SingleQuery query = parseSingleQuery(requestBody, KIND_STATE_COUNT);
        return rowsOnly(runStateCount(query.range(), query.request(), query.query()));
    }

    @PostMapping(path = "/event-counts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> eventCounts(@RequestBody JsonNode requestBody) {
        SingleQuery query = parseSingleQuery(requestBody, KIND_EVENT_COUNT);
        return rowsOnly(runEventCount(query.range(), query.request(), query.query()));
    }

    @PostMapping(path = "/state-transitions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> stateTransitions(@RequestBody JsonNode requestBody) {
        GrafanaStateTransitionRequest request = parseStateTransitionRequest(requestBody);
        GrafanaRangeResolver.ResolvedRange range = GrafanaRangeResolver.resolve(request.range());
        int pointBudget = resolveTimeseriesPointBudget(request.maxDataPoints(), range, TRANSITION_TIMESERIES_STEP);
        String bucket = resolveAdaptiveTimeseriesBucket(
                request.bucket(), request.intervalMs(), range, pointBudget, TRANSITION_TIMESERIES_STEP);
        int limit = Math.min(pointBudget, resolveLimit(range, bucket, pointBudget));

        java.time.Instant start = range.from();
        java.time.Instant end = range.to();
        if (!end.isAfter(start)) {
            Duration step = DurationParser.parse(bucket);
            end = start.plus(step.isZero() ? Duration.ofMinutes(1) : step);
        }

        com.obsinity.service.core.state.query.StateTransitionQueryRequest payload =
                new com.obsinity.service.core.state.query.StateTransitionQueryRequest(
                        request.serviceKey(),
                        request.objectType(),
                        request.attribute(),
                        request.fromStates(),
                        request.toStates(),
                        bucket,
                        start.toString(),
                        end.toString(),
                        new com.obsinity.service.core.state.query.StateTransitionQueryRequest.Limits(0, limit),
                        ResponseFormat.ROW);

        com.obsinity.service.core.state.query.StateTransitionQueryResult result =
                stateTransitionQueryService.runQuery(payload);

        List<String> transitionLabels = buildTransitionLabels(request.fromStates(), request.toStates());
        Map<String, Map<String, Object>> rowsByTime = new LinkedHashMap<>();
        for (com.obsinity.service.core.state.query.StateTransitionQueryWindow window : result.windows()) {
            String time = window.start();
            Map<String, Object> row = rowsByTime.computeIfAbsent(time, key -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("time", key);
                for (String label : transitionLabels) {
                    map.put(label, 0L);
                }
                return map;
            });
            for (com.obsinity.service.core.state.query.StateTransitionQueryWindow.Entry entry : window.transitions()) {
                String label = entry.fromState() + " -> " + entry.toState();
                row.put(label, entry.count());
            }
        }
        return rowsByTime.values().stream().toList();
    }

    @PostMapping(path = "/ratio", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> ratio(@RequestBody JsonNode requestBody) {
        GrafanaRatioRequest request = parseRatioRequest(requestBody);
        String from = request.from();
        String to = request.to();
        if ((from == null || from.isBlank() || to == null || to.isBlank()) && request.range() != null) {
            GrafanaRangeResolver.ResolvedRange range = GrafanaRangeResolver.resolve(request.range());
            from = range.from().toString();
            to = range.to().toString();
        }
        RatioQueryResult result;
        if (request.items() != null && !request.items().isEmpty()) {
            List<AdHocRatioQueryRequest.Item> items = request.items().stream()
                    .map(item -> new AdHocRatioQueryRequest.Item(item.state(), item.transition(), item.label()))
                    .toList();
            result = ratioQueryService.runAdHocQuery(new AdHocRatioQueryRequest(
                    request.serviceKey(),
                    request.name(),
                    request.source(),
                    request.objectType(),
                    request.attribute(),
                    items,
                    from,
                    to,
                    request.value(),
                    request.includeRaw(),
                    request.includePercent(),
                    request.includeRatio(),
                    request.decimals(),
                    request.latestMinute(),
                    request.zeroTotal(),
                    request.missingItem()));
        } else {
            result = ratioQueryService.runQuery(new RatioQueryRequest(request.serviceKey(), request.name(), from, to));
        }
        return result.slices().stream().map(this::toRatioRow).toList();
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(Exception ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", "Bad Request");
        response.put("message", ex.getMessage());
        return response;
    }

    private GrafanaQueryRequest parseRequest(JsonNode requestBody) {
        if (requestBody == null || requestBody.isNull()) {
            return new GrafanaQueryRequest(null, null, null, null, null);
        }
        if (requestBody.isObject()) {
            ObjectNode root = ((ObjectNode) requestBody).deepCopy();
            coerceLong(root, "intervalMs");
            coerceInteger(root, "maxDataPoints");
            JsonNode rangeNode = root.get("range");
            if (rangeNode instanceof ObjectNode range) {
                coerceLong(range, "fromMs");
                coerceLong(range, "toMs");
            }
            GrafanaQueryRequest request = objectMapper.convertValue(root, GrafanaQueryRequest.class);
            if (request.queries() == null || request.queries().isEmpty()) {
                GrafanaSubQuery candidate = extractSingleQuery(root);
                if (candidate != null) {
                    request = new GrafanaQueryRequest(
                            request.range(),
                            request.timezone(),
                            request.intervalMs(),
                            request.maxDataPoints(),
                            List.of(candidate));
                }
            }
            return request;
        }
        return objectMapper.convertValue(requestBody, GrafanaQueryRequest.class);
    }

    private void coerceLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isTextual()) {
            String text = value.asText().trim();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                try {
                    node.put(field, Long.parseLong(text));
                } catch (NumberFormatException ignored) {
                    // Leave as-is; Jackson will report a parse error downstream.
                }
            }
        }
    }

    private void coerceInteger(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isTextual()) {
            String text = value.asText().trim();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                try {
                    node.put(field, Integer.parseInt(text));
                } catch (NumberFormatException ignored) {
                    // Leave as-is; Jackson will report a parse error downstream.
                }
            }
        }
    }

    private GrafanaStateTransitionRequest parseStateTransitionRequest(JsonNode requestBody) {
        if (requestBody == null || requestBody.isNull()) {
            throw new IllegalArgumentException("Grafana state transition payload is required");
        }
        if (requestBody.isObject()) {
            ObjectNode root = ((ObjectNode) requestBody).deepCopy();
            coerceLong(root, "intervalMs");
            coerceInteger(root, "maxDataPoints");
            JsonNode rangeNode = root.get("range");
            if (rangeNode instanceof ObjectNode range) {
                coerceLong(range, "fromMs");
                coerceLong(range, "toMs");
            }
            return objectMapper.convertValue(root, GrafanaStateTransitionRequest.class);
        }
        return objectMapper.convertValue(requestBody, GrafanaStateTransitionRequest.class);
    }

    private GrafanaRatioRequest parseRatioRequest(JsonNode requestBody) {
        if (requestBody == null || requestBody.isNull()) {
            throw new IllegalArgumentException("Grafana ratio payload is required");
        }
        if (requestBody.isObject()) {
            ObjectNode root = ((ObjectNode) requestBody).deepCopy();
            JsonNode rangeNode = root.get("range");
            if (rangeNode instanceof ObjectNode range) {
                coerceLong(range, "fromMs");
                coerceLong(range, "toMs");
            }
            return objectMapper.convertValue(root, GrafanaRatioRequest.class);
        }
        return objectMapper.convertValue(requestBody, GrafanaRatioRequest.class);
    }

    private record GrafanaStateTransitionRequest(
            GrafanaQueryModels.Range range,
            Long intervalMs,
            Integer maxDataPoints,
            String bucket,
            String serviceKey,
            String objectType,
            String attribute,
            List<String> fromStates,
            List<String> toStates) {}

    private record GrafanaRatioRequest(
            GrafanaQueryModels.Range range,
            String serviceKey,
            String name,
            String from,
            String to,
            String source,
            String objectType,
            String attribute,
            List<GrafanaRatioItem> items,
            String value,
            Boolean includeRaw,
            Boolean includePercent,
            Boolean includeRatio,
            Integer decimals,
            Boolean latestMinute,
            String zeroTotal,
            String missingItem) {}

    private record GrafanaRatioItem(String state, String transition, String label) {}

    private List<String> buildTransitionLabels(List<String> fromStates, List<String> toStates) {
        if (fromStates == null || fromStates.isEmpty() || toStates == null || toStates.isEmpty()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (String from : fromStates) {
            if (from == null) continue;
            for (String to : toStates) {
                if (to == null) continue;
                labels.add(from + " -> " + to);
            }
        }
        return labels;
    }

    private Map<String, Object> toRatioRow(RatioQueryResult.RatioSlice slice) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", slice.label());
        row.put("value", slice.value());
        if (slice.percent() != null) {
            row.put("percent", slice.percent());
        }
        if (slice.ratio() != null) {
            row.put("ratio", slice.ratio());
        }
        if (slice.rawValue() != null) {
            row.put("rawValue", slice.rawValue());
        }
        return row;
    }

    private GrafanaResult runHistogram(
            GrafanaRangeResolver.ResolvedRange range, GrafanaQueryRequest request, GrafanaSubQuery query) {
        String bucket = GrafanaBucketResolver.resolveBucket(
                query.bucket(),
                request.intervalMs(),
                request.maxDataPoints(),
                range.fromMs(),
                range.toMs(),
                COUNTER_BUCKETS);
        int limit = resolveLimit(range, bucket, request.maxDataPoints());

        HistogramQueryRequest payload = new HistogramQueryRequest(
                query.serviceKey(),
                query.eventType(),
                query.histogramName(),
                query.filters(),
                bucket,
                range.from().toString(),
                range.to().toString(),
                query.percentiles(),
                new HistogramQueryRequest.Limits(0, limit),
                ResponseFormat.ROW);

        HistogramQueryResult result = histogramQueryService.runQuery(payload);
        List<Double> percentiles =
                query.percentiles() != null && !query.percentiles().isEmpty()
                        ? query.percentiles()
                        : result.defaultPercentiles();

        Map<String, FrameBuilder> frames = new LinkedHashMap<>();
        for (HistogramQueryWindow window : result.windows()) {
            for (HistogramQueryWindow.Series series : window.series()) {
                Map<Double, Double> values = series.percentiles();
                if (values == null || values.isEmpty()) {
                    continue;
                }
                for (Double percentile : percentiles) {
                    if (percentile == null) {
                        continue;
                    }
                    Double value = values.get(percentile);
                    if (value == null) {
                        continue;
                    }
                    String percentileLabel = toPercentileLabel(percentile);
                    Map<String, String> labels = new LinkedHashMap<>();
                    if (series.key() != null) {
                        labels.putAll(series.key());
                    }
                    labels.put("percentile", percentileLabel);
                    String name = query.histogramName() + "." + percentileLabel;
                    FrameBuilder frame = frames.computeIfAbsent(
                            name, n -> FrameBuilder.timeSeries(query.refId(), n, "value", labels));
                    frame.addRow(window.from(), value);
                }
            }
        }

        List<Map<String, Object>> rows = buildPercentileRows(result, percentiles);
        return new GrafanaResult(
                frames.values().stream().map(FrameBuilder::build).toList(), rows);
    }

    private GrafanaResult runStateCount(
            GrafanaRangeResolver.ResolvedRange range, GrafanaQueryRequest request, GrafanaSubQuery query) {
        int pointBudget = resolveTimeseriesPointBudget(request.maxDataPoints(), range, STATE_TIMESERIES_STEP);
        String bucket = resolveAdaptiveTimeseriesBucket(
                query.bucket(), request.intervalMs(), range, pointBudget, STATE_TIMESERIES_STEP);
        int limit = Math.min(pointBudget, resolveLimit(range, bucket, pointBudget));
        java.time.Instant start = range.from();
        java.time.Instant end = range.to();
        if (!end.isAfter(start)) {
            Duration step = DurationParser.parse(bucket);
            end = start.plus(step.isZero() ? Duration.ofMinutes(1) : step);
        }

        StateCountTimeseriesQueryRequest payload = new StateCountTimeseriesQueryRequest(
                query.serviceKey(),
                query.objectType(),
                query.attribute(),
                query.states(),
                bucket,
                start.toString(),
                end.toString(),
                new StateCountTimeseriesQueryRequest.Limits(0, limit),
                ResponseFormat.ROW);

        StateCountTimeseriesQueryResult result = stateCountTimeseriesQueryService.runQuery(payload);
        List<String> resolvedStates = query.states() != null && !query.states().isEmpty()
                ? query.states()
                : result.windows().stream()
                        .flatMap(window -> window.states().stream())
                        .map(StateCountTimeseriesQueryResult.StateCountTimeseriesWindow.Entry::state)
                        .distinct()
                        .toList();
        Map<String, Map<String, Object>> rowsByTime = new LinkedHashMap<>();
        for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow window : result.windows()) {
            String time = window.start();
            Map<String, Object> row = rowsByTime.computeIfAbsent(time, key -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("time", key);
                for (String state : resolvedStates) {
                    map.put(state, 0L);
                }
                return map;
            });
            for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow.Entry entry : window.states()) {
                row.put(entry.state(), entry.count());
            }
        }
        List<Map<String, Object>> rows = rowsByTime.values().stream().toList();

        if (FORMAT_TABLE.equalsIgnoreCase(query.format())) {
            FrameBuilder table = FrameBuilder.table(
                    query.refId(),
                    "state_count",
                    List.of(
                            new Field("time", "time", Map.of(), null, null),
                            new Field("state", "string", Map.of(), null, null),
                            new Field("count", "number", Map.of(), null, null)));
            for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow window : result.windows()) {
                for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow.Entry entry : window.states()) {
                    table.addRow(window.start(), entry.state(), entry.count());
                }
            }
            return new GrafanaResult(List.of(table.build()), rows);
        }

        Map<String, FrameBuilder> frames = new LinkedHashMap<>();
        for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow window : result.windows()) {
            for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow.Entry entry : window.states()) {
                String state = entry.state();
                String name = query.attribute() + "." + state;
                Map<String, String> labels = Map.of("state", state);
                FrameBuilder frame =
                        frames.computeIfAbsent(name, n -> FrameBuilder.timeSeries(query.refId(), n, "count", labels));
                frame.addRow(window.start(), entry.count());
            }
        }

        return new GrafanaResult(
                frames.values().stream().map(FrameBuilder::build).toList(), rows);
    }

    private GrafanaResult runStateCountSnapshot(GrafanaQueryRequest request, GrafanaSubQuery query) {
        Integer limit = request.maxDataPoints() != null && request.maxDataPoints() > 0 ? request.maxDataPoints() : null;
        StateCountQueryRequest.Limits limits = limit != null ? new StateCountQueryRequest.Limits(0, limit) : null;
        StateCountQueryRequest payload = new StateCountQueryRequest(
                query.serviceKey(), query.objectType(), query.attribute(), query.states(), limits, ResponseFormat.ROW);
        StateCountQueryResult result = stateCountQueryService.runQuery(payload);
        List<StateCountQueryResult.StateCountEntry> entries;
        if (query.states() != null && !query.states().isEmpty()) {
            Map<String, Long> byState = new LinkedHashMap<>();
            for (String state : query.states()) {
                if (state != null && !state.isBlank()) {
                    byState.put(state, 0L);
                }
            }
            for (StateCountQueryResult.StateCountEntry entry : result.states()) {
                byState.put(entry.state(), entry.count());
            }
            entries = byState.entrySet().stream()
                    .map(e -> new StateCountQueryResult.StateCountEntry(e.getKey(), e.getValue()))
                    .toList();
        } else {
            entries = result.states();
        }
        List<Map<String, Object>> rows = entries.stream()
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("state", entry.state());
                    row.put("count", entry.count());
                    return row;
                })
                .toList();
        FrameBuilder table = FrameBuilder.table(
                query.refId(),
                "state_count",
                List.of(
                        new Field("state", "string", Map.of(), null, null),
                        new Field("count", "number", Map.of(), null, null)));
        for (StateCountQueryResult.StateCountEntry entry : entries) {
            table.addRow(entry.state(), entry.count());
        }
        return new GrafanaResult(List.of(table.build()), rows);
    }

    private GrafanaResult runEventCount(
            GrafanaRangeResolver.ResolvedRange range, GrafanaQueryRequest request, GrafanaSubQuery query) {
        String bucket = GrafanaBucketResolver.resolveBucket(
                query.bucket(),
                request.intervalMs(),
                request.maxDataPoints(),
                range.fromMs(),
                range.toMs(),
                COUNTER_BUCKETS);
        int limit = resolveLimit(range, bucket, request.maxDataPoints());

        CounterQueryRequest payload = new CounterQueryRequest(
                query.serviceKey(),
                query.eventType(),
                "event_count",
                query.filters(),
                bucket,
                null,
                null,
                new CounterQueryRequest.Limits(0, limit),
                ResponseFormat.ROW);

        CounterQueryResult result = counterQueryService.runQuery(payload);
        Map<String, FrameBuilder> frames = new LinkedHashMap<>();
        List<Map<String, Object>> rows = result.windows().stream()
                .flatMap(window -> window.counts().stream()
                        .filter(entry -> entry.count() > 0)
                        .map(entry -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("from", window.from());
                            row.put("to", window.to());
                            if (entry.key() != null) {
                                row.putAll(entry.key());
                            }
                            row.put("count", entry.count());
                            return row;
                        }))
                .toList();

        for (CounterQueryWindow window : result.windows()) {
            for (CounterQueryWindow.CountEntry entry : window.counts()) {
                Map<String, String> labels = entry.key() != null ? entry.key() : Map.of();
                String name = query.eventType() + ".count";
                String frameKey = name + labels.toString();
                FrameBuilder frame = frames.computeIfAbsent(
                        frameKey, n -> FrameBuilder.timeSeries(query.refId(), name, "count", labels));
                frame.addRow(window.from(), entry.count());
            }
        }

        return new GrafanaResult(
                frames.values().stream().map(FrameBuilder::build).toList(), rows);
    }

    private List<Map<String, Object>> buildPercentileRows(HistogramQueryResult result, List<Double> percentiles) {
        if (result == null || result.windows() == null || result.windows().isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (HistogramQueryWindow window : result.windows()) {
            String time = window.from();
            if (time == null) {
                continue;
            }
            Map<String, Object> row = rows.computeIfAbsent(time, t -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("time", t);
                return map;
            });
            for (HistogramQueryWindow.Series series : window.series()) {
                Map<Double, Double> values = series.percentiles();
                if (values == null || values.isEmpty()) {
                    continue;
                }
                for (Double percentile : percentiles) {
                    if (percentile == null) {
                        continue;
                    }
                    Double value = values.get(percentile);
                    if (value == null) {
                        continue;
                    }
                    row.put(toPercentileLabel(percentile), value);
                }
            }
            if (percentiles != null && !percentiles.isEmpty()) {
                boolean complete = true;
                for (Double percentile : percentiles) {
                    if (percentile == null) {
                        continue;
                    }
                    if (!row.containsKey(toPercentileLabel(percentile))) {
                        complete = false;
                        break;
                    }
                }
                if (!complete) {
                    rows.remove(time);
                }
            }
        }
        return rows.values().stream().toList();
    }

    private int resolveLimit(GrafanaRangeResolver.ResolvedRange range, String bucket, Integer maxDataPoints) {
        Duration duration = DurationParser.parse(bucket);
        long bucketMs = duration.toMillis();
        long rangeMs = Math.max(0L, range.toMs() - range.fromMs());
        long expectedPoints = bucketMs > 0 ? (long) Math.ceil((double) rangeMs / (double) bucketMs) : 0L;
        long limit = expectedPoints > 0 ? expectedPoints : (maxDataPoints != null ? maxDataPoints : 1000);
        if (maxDataPoints != null && maxDataPoints > 0) {
            limit = Math.min(limit, maxDataPoints);
        }
        long sixHoursMs = Duration.ofHours(6).toMillis();
        long minPoints = bucketMs > 0 ? (long) Math.ceil((double) sixHoursMs / (double) bucketMs) : 0L;
        if (minPoints > 0) {
            limit = Math.max(limit, minPoints);
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, limit));
    }

    private int resolveTimeseriesPointBudget(
            Integer maxDataPoints, GrafanaRangeResolver.ResolvedRange range, Duration minimumStep) {
        if (maxDataPoints != null && maxDataPoints > 0) {
            return Math.min(timeseriesPointCap, maxDataPoints);
        }
        long rangeMs = Math.max(0L, range.toMs() - range.fromMs());
        long stepMs = Math.max(1L, minimumStep.toMillis());
        long rangePoints = (long) Math.ceil((double) rangeMs / (double) stepMs);
        if (rangePoints <= 0) {
            return 1;
        }
        return (int) Math.min(timeseriesPointCap, rangePoints);
    }

    private String resolveAdaptiveTimeseriesBucket(
            String requestedBucket,
            Long intervalMs,
            GrafanaRangeResolver.ResolvedRange range,
            int pointBudget,
            Duration minimumStep) {
        long bucketMs = minimumStep.toMillis();
        if (intervalMs != null && intervalMs > 0) {
            bucketMs = Math.max(bucketMs, intervalMs);
        }
        if (requestedBucket != null && !requestedBucket.isBlank()) {
            bucketMs = Math.max(bucketMs, DurationParser.parse(requestedBucket).toMillis());
        }

        long rangeMs = Math.max(0L, range.toMs() - range.fromMs());
        if (rangeMs > 0 && pointBudget > 0) {
            long idealMs = (long) Math.ceil((double) rangeMs / (double) pointBudget);
            bucketMs = Math.max(bucketMs, idealMs);
        }

        long stepMs = Math.max(1L, minimumStep.toMillis());
        bucketMs = roundUpToMultiple(bucketMs, stepMs);
        return GrafanaBucketResolver.toDurationString(bucketMs);
    }

    private long roundUpToMultiple(long value, long step) {
        if (step <= 0) {
            return value;
        }
        return ((value + step - 1) / step) * step;
    }

    private String toPercentileLabel(double percentile) {
        double scaled = percentile * 100.0d;
        long rounded = Math.round(scaled);
        if (Math.abs(scaled - rounded) < 0.0001d) {
            return "p" + rounded;
        }
        String text = Double.toString(scaled).replace(".", "_");
        return "p" + text;
    }

    private SingleQuery parseSingleQuery(JsonNode requestBody, String defaultKind) {
        GrafanaQueryRequest request = parseRequest(requestBody);
        GrafanaSubQuery query = firstQuery(request);
        if (query == null && requestBody instanceof ObjectNode root) {
            query = extractSingleQuery(root);
        }
        query = normalizeQuery(query, defaultKind);
        GrafanaRangeResolver.ResolvedRange range = GrafanaRangeResolver.resolve(request.range());
        return new SingleQuery(range, request, query);
    }

    private GrafanaSubQuery firstQuery(GrafanaQueryRequest request) {
        if (request == null || request.queries() == null) {
            return null;
        }
        for (GrafanaSubQuery query : request.queries()) {
            if (query != null) {
                return query;
            }
        }
        return null;
    }

    private GrafanaSubQuery extractSingleQuery(ObjectNode root) {
        JsonNode queryNode = root.get("query");
        if (queryNode != null && queryNode.isObject()) {
            return objectMapper.convertValue(queryNode, GrafanaSubQuery.class);
        }
        GrafanaSubQuery candidate = objectMapper.convertValue(root, GrafanaSubQuery.class);
        if (candidate.serviceKey() == null
                && candidate.eventType() == null
                && candidate.objectType() == null
                && candidate.histogramName() == null
                && candidate.attribute() == null
                && candidate.states() == null
                && candidate.filters() == null) {
            return null;
        }
        return candidate;
    }

    private GrafanaSubQuery normalizeQuery(GrafanaSubQuery query, String defaultKind) {
        if (query == null) {
            throw new IllegalArgumentException("Grafana query payload is required");
        }
        String refId = query.refId();
        if (refId == null || refId.isBlank()) {
            refId = "A";
        }
        String kind = query.kind();
        if (kind == null || kind.isBlank()) {
            kind = defaultKind;
        }
        return new GrafanaSubQuery(
                refId,
                kind,
                query.format(),
                query.bucket(),
                query.serviceKey(),
                query.eventType(),
                query.histogramName(),
                query.filters(),
                query.percentiles(),
                query.groupBy(),
                query.objectType(),
                query.attribute(),
                query.states());
    }

    private record SingleQuery(
            GrafanaRangeResolver.ResolvedRange range, GrafanaQueryRequest request, GrafanaSubQuery query) {}

    private List<Map<String, Object>> rowsOnly(GrafanaResult result) {
        if (result == null || result.rows() == null) {
            return List.of();
        }
        return result.rows();
    }

    private static final class FrameBuilder {
        private static final Map<String, String> TIME_TYPE_INFO = Map.of("frame", "time.Time");

        private final String refId;
        private final String name;
        private final List<Field> fields;
        private final List<List<Object>> values;

        private FrameBuilder(String refId, String name, List<Field> fields) {
            this.refId = refId;
            this.name = name;
            this.fields = fields;
            this.values = new ArrayList<>(fields.size());
            for (int i = 0; i < fields.size(); i++) {
                values.add(new ArrayList<>());
            }
        }

        static FrameBuilder timeSeries(String refId, String name, String valueField, Map<String, String> labels) {
            List<Field> fields = List.of(
                    new Field("time", "time", Map.of(), null, TIME_TYPE_INFO),
                    new Field(valueField, "number", Map.of(), labels, null));
            return new FrameBuilder(refId, name, fields);
        }

        static FrameBuilder table(String refId, String name, List<Field> fields) {
            return new FrameBuilder(refId, name, fields);
        }

        void addRow(Object... row) {
            for (int i = 0; i < row.length && i < values.size(); i++) {
                values.get(i).add(row[i]);
            }
        }

        Frame build() {
            Schema schema = new Schema(refId, name, fields);
            FrameData data = new FrameData(values);
            return new Frame(schema, data, null, null);
        }
    }
}

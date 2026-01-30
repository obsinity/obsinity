package com.obsinity.controller.rest.grafana;

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
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryRequest;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryResult;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/grafana", produces = MediaType.APPLICATION_JSON_VALUE)
public class GrafanaQueryController {

    private static final String KIND_HISTOGRAM = "histogram_percentiles";
    private static final String KIND_STATE_COUNT = "state_count";
    private static final String KIND_EVENT_COUNT = "event_count";
    private static final String FORMAT_TABLE = "table";

    private final HistogramQueryService histogramQueryService;
    private final StateCountTimeseriesQueryService stateCountTimeseriesQueryService;
    private final CounterQueryService counterQueryService;

    public GrafanaQueryController(
            HistogramQueryService histogramQueryService,
            StateCountTimeseriesQueryService stateCountTimeseriesQueryService,
            CounterQueryService counterQueryService) {
        this.histogramQueryService = histogramQueryService;
        this.stateCountTimeseriesQueryService = stateCountTimeseriesQueryService;
        this.counterQueryService = counterQueryService;
    }

    @PostMapping(path = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public GrafanaQueryResponse query(@RequestBody GrafanaQueryRequest request) {
        GrafanaRangeResolver.ResolvedRange range = GrafanaRangeResolver.resolve(request.range());
        List<GrafanaResult> results = new ArrayList<>();

        if (request.queries() == null) {
            return new GrafanaQueryResponse(List.of());
        }

        for (GrafanaSubQuery query : request.queries()) {
            if (query == null) {
                continue;
            }
            String kind = query.kind();
            if (KIND_HISTOGRAM.equals(kind)) {
                results.add(runHistogram(range, request, query));
            } else if (KIND_STATE_COUNT.equals(kind)) {
                results.add(runStateCount(range, request, query));
            } else if (KIND_EVENT_COUNT.equals(kind)) {
                results.add(runEventCount(range, request, query));
            } else {
                results.add(new GrafanaResult(query.refId(), List.of()));
            }
        }

        return new GrafanaQueryResponse(results);
    }

    private GrafanaResult runHistogram(
            GrafanaRangeResolver.ResolvedRange range, GrafanaQueryRequest request, GrafanaSubQuery query) {
        String bucket = GrafanaBucketResolver.resolveBucket(
                query.bucket(), request.intervalMs(), request.maxDataPoints(), range.fromMs(), range.toMs());
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
                            name, n -> FrameBuilder.timeSeries(n, "value", labels));
                    frame.addRow(window.from(), value);
                }
            }
        }

        return new GrafanaResult(query.refId(), frames.values().stream().map(FrameBuilder::build).toList());
    }

    private GrafanaResult runStateCount(
            GrafanaRangeResolver.ResolvedRange range, GrafanaQueryRequest request, GrafanaSubQuery query) {
        String bucket = GrafanaBucketResolver.resolveBucket(
                query.bucket(), request.intervalMs(), request.maxDataPoints(), range.fromMs(), range.toMs());
        int limit = resolveLimit(range, bucket, request.maxDataPoints());

        StateCountTimeseriesQueryRequest payload = new StateCountTimeseriesQueryRequest(
                query.serviceKey(),
                query.objectType(),
                query.attribute(),
                query.states(),
                bucket,
                range.from().toString(),
                range.to().toString(),
                new StateCountTimeseriesQueryRequest.Limits(0, limit),
                ResponseFormat.ROW);

        StateCountTimeseriesQueryResult result = stateCountTimeseriesQueryService.runQuery(payload);
        if (FORMAT_TABLE.equalsIgnoreCase(query.format())) {
            FrameBuilder table = FrameBuilder.table(
                    "state_count",
                    List.of(new Field("time", "time", null), new Field("state", "string", null), new Field("count", "number", null)));
            for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow window : result.windows()) {
                for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow.Entry entry : window.states()) {
                    table.addRow(window.start(), entry.state(), entry.count());
                }
            }
            return new GrafanaResult(query.refId(), List.of(table.build()));
        }

        Map<String, FrameBuilder> frames = new LinkedHashMap<>();
        for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow window : result.windows()) {
            for (StateCountTimeseriesQueryResult.StateCountTimeseriesWindow.Entry entry : window.states()) {
                String state = entry.state();
                String name = query.attribute() + "." + state;
                Map<String, String> labels = Map.of("state", state);
                FrameBuilder frame = frames.computeIfAbsent(
                        name, n -> FrameBuilder.timeSeries(n, "count", labels));
                frame.addRow(window.start(), entry.count());
            }
        }

        return new GrafanaResult(query.refId(), frames.values().stream().map(FrameBuilder::build).toList());
    }

    private GrafanaResult runEventCount(
            GrafanaRangeResolver.ResolvedRange range, GrafanaQueryRequest request, GrafanaSubQuery query) {
        String bucket = GrafanaBucketResolver.resolveBucket(
                query.bucket(), request.intervalMs(), request.maxDataPoints(), range.fromMs(), range.toMs());
        int limit = resolveLimit(range, bucket, request.maxDataPoints());

        CounterQueryRequest payload = new CounterQueryRequest(
                query.serviceKey(),
                query.eventType(),
                "event_count",
                query.filters(),
                bucket,
                range.from().toString(),
                range.to().toString(),
                new CounterQueryRequest.Limits(0, limit),
                ResponseFormat.ROW);

        CounterQueryResult result = counterQueryService.runQuery(payload);
        Map<String, FrameBuilder> frames = new LinkedHashMap<>();

        for (CounterQueryWindow window : result.windows()) {
            for (CounterQueryWindow.CountEntry entry : window.counts()) {
                Map<String, String> labels = entry.key() != null ? entry.key() : Map.of();
                String name = query.eventType() + ".count";
                String frameKey = name + labels.toString();
                FrameBuilder frame = frames.computeIfAbsent(
                        frameKey, n -> FrameBuilder.timeSeries(name, "count", labels));
                frame.addRow(window.from(), entry.count());
            }
        }

        return new GrafanaResult(query.refId(), frames.values().stream().map(FrameBuilder::build).toList());
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
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, limit));
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

    private static final class FrameBuilder {
        private final String name;
        private final List<Field> fields;
        private final List<List<Object>> values;

        private FrameBuilder(String name, List<Field> fields) {
            this.name = name;
            this.fields = fields;
            this.values = new ArrayList<>();
        }

        static FrameBuilder timeSeries(String name, String valueField, Map<String, String> labels) {
            List<Field> fields = List.of(new Field("time", "time", null), new Field(valueField, "number", labels));
            return new FrameBuilder(name, fields);
        }

        static FrameBuilder table(String name, List<Field> fields) {
            return new FrameBuilder(name, fields);
        }

        void addRow(Object... row) {
            values.add(Arrays.asList(row));
        }

        DataFrame build() {
            return new DataFrame(name, fields, values);
        }
    }
}

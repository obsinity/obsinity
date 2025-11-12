package com.obsinity.service.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.DDSketches;
import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.EventTypeConfig;
import com.obsinity.service.core.config.HistogramConfig;
import com.obsinity.service.core.config.HistogramSpec;
import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.CounterGranularity;
import com.obsinity.service.core.counter.CounterHashService;
import com.obsinity.service.core.counter.DurationParser;
import com.obsinity.service.core.histogram.HistogramQueryWindow.Series;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HistogramQueryService {

    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;

    private final ConfigLookup configLookup;
    private final ServicesCatalogRepository servicesCatalogRepository;
    private final HistogramQueryRepository repository;
    private final CounterHashService hashService;

    public HistogramQueryResult runQuery(HistogramQueryRequest request) {
        Objects.requireNonNull(request, "query request");
        if (request.serviceKey() == null || request.serviceKey().isBlank()) {
            throw new IllegalArgumentException("serviceKey is required");
        }
        if (request.eventType() == null || request.eventType().isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (request.histogramName() == null || request.histogramName().isBlank()) {
            throw new IllegalArgumentException("histogramName is required");
        }

        UUID serviceId = servicesCatalogRepository.findIdByServiceKey(request.serviceKey());
        if (serviceId == null) {
            throw new IllegalArgumentException("Unknown service key: " + request.serviceKey());
        }

        EventTypeConfig eventConfig = configLookup
                .get(serviceId, request.eventType())
                .orElseThrow(() -> new IllegalArgumentException("Unknown event type: " + request.eventType()));
        HistogramConfig histogramConfig = eventConfig.histograms().stream()
                .filter(h -> h.name().equalsIgnoreCase(request.histogramName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown histogram: " + request.histogramName() + " for event " + request.eventType()));

        HistogramSpec spec = histogramConfig.spec();
        CounterGranularity granularity = spec != null ? spec.granularity() : CounterGranularity.S5;
        Duration requestedInterval =
                request.interval() != null ? DurationParser.parse(request.interval()) : granularity.duration();
        if (requestedInterval.compareTo(granularity.duration()) < 0) {
            throw new IllegalArgumentException(
                    "Requested interval " + requestedInterval + " is finer than histogram granularity " + granularity);
        }

        CounterBucket bucket = resolveBucket(granularity, requestedInterval);

        Instant defaultEnd = Instant.now();
        Instant defaultStart = defaultEnd.minus(Duration.ofDays(14));
        Instant earliestData = repository.findEarliestTimestamp(histogramConfig.id());
        if (earliestData != null && earliestData.isAfter(defaultStart)) {
            defaultStart = earliestData;
        }

        Instant start = request.start() != null ? Instant.parse(request.start()) : defaultStart;
        if (earliestData != null && start.isBefore(earliestData)) {
            start = earliestData;
        }

        Instant end = request.end() != null ? Instant.parse(request.end()) : defaultEnd;
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("The requested end time must be after start");
        }

        Instant alignedStart = bucket.align(start);
        Instant alignedEnd = bucket.alignToNext(end);

        List<Map<String, String>> keyMatrix =
                expandKeys(spec != null ? spec.keyDimensions() : List.of(), request.key());
        List<String> hashes =
                keyMatrix.stream().map(hashService::getOrCreateHash).collect(Collectors.toList());
        String[] hashArray = hashes.toArray(String[]::new);

        List<Double> defaultPercentiles =
                spec != null ? spec.percentiles() : List.of(0.5d, 0.9d, 0.95d, 0.99d);
        List<Double> percentiles = request.percentiles() != null && !request.percentiles().isEmpty()
                ? request.percentiles()
                : defaultPercentiles;

        int offset = request.limits() != null && request.limits().offset() != null
                ? request.limits().offset()
                : 0;
        int limit = request.limits() != null && request.limits().limit() != null
                ? request.limits().limit()
                : Integer.MAX_VALUE;

        List<HistogramQueryWindow> windows = new ArrayList<>();
        Duration step = requestedInterval;
        Instant cursor = alignedStart.plus(step.multipliedBy(offset));
        int intervalsAdded = 0;

        while (cursor.isBefore(alignedEnd) && intervalsAdded < limit) {
            Instant next = cursor.plus(step);
            List<HistogramQueryRepository.Row> rows =
                    repository.fetchRange(histogramConfig.id(), bucket, hashArray, cursor, next);
            Map<String, List<HistogramQueryRepository.Row>> rowsByHash =
                    rows.stream().collect(Collectors.groupingBy(HistogramQueryRepository.Row::keyHash));

            List<Series> series = new ArrayList<>(keyMatrix.size());
            for (int i = 0; i < keyMatrix.size(); i++) {
                Map<String, String> key = keyMatrix.get(i);
                String hash = hashes.get(i);
                HistogramAggregation aggregation = aggregate(rowsByHash.get(hash), spec);
                Map<Double, Double> percentileValues =
                        computePercentiles(aggregation.sketch(), percentiles, aggregation.samples());
                Double mean = aggregation.samples() > 0 ? aggregation.sum() / aggregation.samples() : null;
                series.add(new Series(
                        key,
                        aggregation.samples(),
                        aggregation.sum(),
                        mean,
                        percentileValues,
                        aggregation.overflowLow(),
                        aggregation.overflowHigh()));
            }

            windows.add(new HistogramQueryWindow(ISO_INSTANT.format(cursor), ISO_INSTANT.format(next), series));
            cursor = next;
            intervalsAdded++;
        }

        return new HistogramQueryResult(
                windows, offset, limit, computeTotalIntervals(alignedStart, alignedEnd, step), defaultPercentiles);
    }

    private HistogramAggregation aggregate(List<HistogramQueryRepository.Row> rows, HistogramSpec spec) {
        HistogramAggregation aggregation = new HistogramAggregation(createSketch(spec), 0, 0.0d, 0, 0);
        if (rows == null || rows.isEmpty()) {
            return aggregation;
        }
        for (HistogramQueryRepository.Row row : rows) {
            DDSketch rowSketch = HistogramSketchCodec.deserialize(row.sketchPayload());
            if (rowSketch != null) {
                aggregation.sketch().mergeWith(rowSketch);
            }
            aggregation
                    .addSamples(row.sampleCount())
                    .addSum(row.sampleSum())
                    .addOverflowLow(row.overflowLow())
                    .addOverflowHigh(row.overflowHigh());
        }
        return aggregation;
    }

    private DDSketch createSketch(HistogramSpec spec) {
        HistogramSpec.SketchSpec sketchSpec = spec != null ? spec.sketchSpec() : null;
        double accuracy = sketchSpec != null ? sketchSpec.relativeAccuracy() : 0.01d;
        return DDSketches.unboundedDense(accuracy);
    }

    private Map<Double, Double> computePercentiles(DDSketch sketch, List<Double> percentiles, long samples) {
        Map<Double, Double> values = new LinkedHashMap<>();
        if (sketch == null || samples == 0) {
            return values;
        }
        for (Double percentile : percentiles) {
            if (percentile == null) {
                continue;
            }
            double q = percentile;
            if (Double.isNaN(q)) continue;
            if (q < 0d) q = 0d;
            if (q > 1d) q = 1d;
            values.put(percentile, sketch.getValueAtQuantile(q));
        }
        return values;
    }

    private CounterBucket resolveBucket(CounterGranularity granularity, Duration requested) {
        List<CounterBucket> candidates = CounterBucket.valuesSortedByAscendingDuration();
        EnumSet<CounterBucket> allowed = granularity.materialisedBuckets();
        for (CounterBucket candidate : candidates) {
            if (!allowed.contains(candidate)) {
                continue;
            }
            if (requested.toMillis() % candidate.duration().toMillis() == 0) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("No compatible bucket for interval " + requested);
    }

    private List<Map<String, String>> expandKeys(List<String> keyedKeys, Map<String, List<String>> matrix) {
        if (keyedKeys == null || keyedKeys.isEmpty()) {
            return List.of(Map.of());
        }
        return recurseKeys(keyedKeys, 0, matrix, new java.util.LinkedHashMap<>());
    }

    private List<Map<String, String>> recurseKeys(
            List<String> keys, int index, Map<String, List<String>> matrix, Map<String, String> current) {
        if (index >= keys.size()) {
            return List.of(Map.copyOf(current));
        }
        String key = keys.get(index);
        List<String> values = matrix != null ? matrix.get(key) : null;
        if (values == null || values.isEmpty()) {
            return List.of(Map.copyOf(current));
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (String value : values) {
            current.put(key, value);
            result.addAll(recurseKeys(keys, index + 1, matrix, current));
            current.remove(key);
        }
        return result;
    }

    private int computeTotalIntervals(Instant start, Instant end, Duration step) {
        long millis = Duration.between(start, end).toMillis();
        long stepMillis = step.toMillis();
        return (int) Math.max(0, millis / stepMillis);
    }

    private static final class HistogramAggregation {
        private final DDSketch sketch;
        private long samples;
        private double sum;
        private long overflowLow;
        private long overflowHigh;

        HistogramAggregation(DDSketch sketch, long samples, double sum, long overflowLow, long overflowHigh) {
            this.sketch = sketch;
            this.samples = samples;
            this.sum = sum;
            this.overflowLow = overflowLow;
            this.overflowHigh = overflowHigh;
        }

        DDSketch sketch() {
            return sketch;
        }

        long samples() {
            return samples;
        }

        double sum() {
            return sum;
        }

        long overflowLow() {
            return overflowLow;
        }

        long overflowHigh() {
            return overflowHigh;
        }

        HistogramAggregation addSamples(long delta) {
            this.samples += delta;
            return this;
        }

        HistogramAggregation addSum(double delta) {
            this.sum += delta;
            return this;
        }

        HistogramAggregation addOverflowLow(long delta) {
            this.overflowLow += delta;
            return this;
        }

        HistogramAggregation addOverflowHigh(long delta) {
            this.overflowHigh += delta;
            return this;
        }
    }
}

package com.obsinity.service.core.state.query;

import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.DurationParser;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryResult.StateCountTimeseriesWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StateCountTimeseriesQueryService {

    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;
    private static final List<CounterBucket> SUPPORTED_BUCKETS =
            List.of(CounterBucket.M1, CounterBucket.M5, CounterBucket.H1, CounterBucket.D1);

    private final ServicesCatalogRepository servicesCatalogRepository;
    private final StateCountTimeseriesQueryRepository repository;

    public StateCountTimeseriesQueryService(
            ServicesCatalogRepository servicesCatalogRepository, StateCountTimeseriesQueryRepository repository) {
        this.servicesCatalogRepository = servicesCatalogRepository;
        this.repository = repository;
    }

    public StateCountTimeseriesQueryResult runQuery(StateCountTimeseriesQueryRequest request) {
        validate(request);
        UUID serviceId = servicesCatalogRepository.findIdByServiceKey(request.serviceKey());
        if (serviceId == null) {
            throw new IllegalArgumentException("Unknown service key: " + request.serviceKey());
        }

        Duration requestedInterval =
                request.interval() != null ? DurationParser.parse(request.interval()) : CounterBucket.M1.duration();
        resolveBucket(requestedInterval);
        CounterBucket queryBucket = CounterBucket.M1;

        Instant earliest =
                repository.findEarliestTimestamp(serviceId, request.objectType(), request.attribute(), queryBucket);
        Instant latest =
                repository.findLatestTimestamp(serviceId, request.objectType(), request.attribute(), queryBucket);
        Instant defaultEnd = latest != null ? latest : Instant.now();
        Instant defaultStart = defaultEnd.minus(Duration.ofDays(7));
        if (earliest != null && defaultStart.isBefore(earliest)) {
            defaultStart = earliest;
        }

        Instant start = request.start() != null ? Instant.parse(request.start()) : defaultStart;
        if (earliest != null && start.isBefore(earliest)) {
            start = earliest;
        }
        Instant end = request.end() != null ? Instant.parse(request.end()) : defaultEnd;
        if (!end.isAfter(start)) {
            end = start.plus(requestedInterval.isZero() ? Duration.ofMinutes(1) : requestedInterval);
        }

        Instant alignedStart = truncateToMinute(start);
        Instant alignedEnd = truncateToMinute(end);
        if (!alignedEnd.isAfter(alignedStart)) {
            alignedEnd = alignedStart.plus(requestedInterval);
        }
        Instant firstInRange = repository.findEarliestTimestampInRange(
                serviceId,
                request.objectType(),
                request.attribute(),
                request.states(),
                queryBucket,
                alignedStart,
                alignedEnd);
        if (firstInRange == null) {
            return new StateCountTimeseriesQueryResult(List.of(), 0, 0, 0, start, end);
        }
        if (firstInRange.isAfter(alignedStart)) {
            alignedStart = firstInRange;
        }

        int offset = request.limits() != null && request.limits().offset() != null
                ? Math.max(0, request.limits().offset())
                : 0;
        int limit = request.limits() != null && request.limits().limit() != null
                ? Math.max(1, request.limits().limit())
                : Integer.MAX_VALUE;

        List<StateCountTimeseriesWindow> windows = new ArrayList<>();
        Duration step = requestedInterval;
        Instant cursor = alignedStart.plus(step.multipliedBy(offset));
        int emittedWindows = 0;
        List<String> requestedStates = request.states() == null ? List.of() : request.states();
        Map<String, Long> lastKnownCounts = new LinkedHashMap<>();

        while (cursor.isBefore(alignedEnd) && emittedWindows < limit) {
            Instant next = cursor.plus(step);
            Instant rangeStart = queryBucket.align(cursor);
            Instant rangeEnd = queryBucket.align(next);
            if (!rangeEnd.isAfter(rangeStart)) {
                rangeEnd = rangeStart.plus(queryBucket.duration());
            }
            List<StateCountTimeseriesQueryRepository.Row> rows = repository.fetchRowsInRange(
                    serviceId, request.objectType(), request.attribute(), null, queryBucket, rangeStart, rangeEnd);
            Map<String, Long> countsForWindow = new LinkedHashMap<>();
            if (!rows.isEmpty()) {
                Map<String, Long> latestPerState = new LinkedHashMap<>();
                for (StateCountTimeseriesQueryRepository.Row row : rows) {
                    latestPerState.put(row.stateValue(), row.count());
                }
                if (requestedStates.isEmpty()) {
                    countsForWindow.putAll(latestPerState);
                } else {
                    for (String state : requestedStates) {
                        countsForWindow.put(state, latestPerState.getOrDefault(state, 0L));
                    }
                }
                lastKnownCounts = new LinkedHashMap<>(countsForWindow);
            } else if (!lastKnownCounts.isEmpty()) {
                countsForWindow.putAll(lastKnownCounts);
            }

            if (!countsForWindow.isEmpty()) {
                List<StateCountTimeseriesWindow.Entry> entries = countsForWindow.entrySet().stream()
                        .map(e -> new StateCountTimeseriesWindow.Entry(e.getKey(), e.getValue()))
                        .toList();
                windows.add(
                        new StateCountTimeseriesWindow(ISO_INSTANT.format(cursor), ISO_INSTANT.format(next), entries));
                emittedWindows++;
            }
            cursor = next;
        }

        return new StateCountTimeseriesQueryResult(
                windows, offset, limit, computeTotalIntervals(alignedStart, alignedEnd, step), start, end);
    }

    private void validate(StateCountTimeseriesQueryRequest request) {
        Objects.requireNonNull(request, "query request");
        if (request.serviceKey() == null || request.serviceKey().isBlank()) {
            throw new IllegalArgumentException("serviceKey is required");
        }
        if (request.objectType() == null || request.objectType().isBlank()) {
            throw new IllegalArgumentException("objectType is required");
        }
        if (request.attribute() == null || request.attribute().isBlank()) {
            throw new IllegalArgumentException("attribute is required");
        }
    }

    private int computeTotalIntervals(Instant start, Instant end, Duration step) {
        long millis = Duration.between(start, end).toMillis();
        long stepMillis = step.toMillis();
        return (int) Math.max(0, millis / stepMillis);
    }

    private CounterBucket resolveBucket(Duration requested) {
        for (CounterBucket bucket : SUPPORTED_BUCKETS) {
            if (requested.equals(bucket.duration())) {
                return bucket;
            }
        }
        Duration base = CounterBucket.M1.duration();
        if (requested.toMillis() % base.toMillis() == 0) {
            return CounterBucket.M1;
        }
        throw new IllegalArgumentException("Unsupported interval for state count snapshots: " + requested);
    }

    private Instant truncateToMinute(Instant instant) {
        CounterBucket bucket = CounterBucket.M1;
        return bucket.align(instant);
    }
}

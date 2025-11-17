package com.obsinity.service.core.state.query;

import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.DurationParser;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryResult.StateCountTimeseriesWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
        CounterBucket resolvedBucket = resolveBucket(requestedInterval);
        CounterBucket queryBucket =
                requestedInterval.equals(resolvedBucket.duration()) ? resolvedBucket : CounterBucket.M1;

        Instant defaultEnd = Instant.now();
        Instant earliest =
                repository.findEarliestTimestamp(serviceId, request.objectType(), request.attribute(), queryBucket);
        Instant defaultStart = earliest != null
                ? truncateToMinute(earliest)
                : truncateToMinute(defaultEnd.minus(Duration.ofDays(7)));

        Instant start = request.start() != null ? Instant.parse(request.start()) : defaultStart;
        if (earliest != null && start.isBefore(earliest)) {
            start = earliest;
        }
        Instant end = request.end() != null ? Instant.parse(request.end()) : defaultEnd;
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("The requested end time must be after start");
        }

        Instant alignedStart = truncateToMinute(start);
        Instant alignedEnd = truncateToMinute(end);
        if (!alignedEnd.isAfter(alignedStart)) {
            alignedEnd = alignedStart.plus(requestedInterval);
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
        int intervalsAdded = 0;

        while (cursor.isBefore(alignedEnd) && intervalsAdded < limit) {
            Instant next = cursor.plus(step);
            Instant fetchTs = queryBucket.align(cursor);
            List<StateCountTimeseriesQueryRepository.Row> rows = repository.fetchWindow(
                    serviceId, request.objectType(), request.attribute(), request.states(), queryBucket, fetchTs);
            List<StateCountTimeseriesWindow.Entry> entries = rows.stream()
                    .map(r -> new StateCountTimeseriesWindow.Entry(r.stateValue(), r.count()))
                    .toList();
            windows.add(new StateCountTimeseriesWindow(ISO_INSTANT.format(cursor), ISO_INSTANT.format(next), entries));
            cursor = next;
            intervalsAdded++;
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

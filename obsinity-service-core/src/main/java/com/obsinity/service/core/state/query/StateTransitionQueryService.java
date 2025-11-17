package com.obsinity.service.core.state.query;

import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.CounterGranularity;
import com.obsinity.service.core.counter.DurationParser;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StateTransitionQueryService {

    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;
    private static final String WILDCARD = "*";
    private static final String NO_STATE_LABEL = "(none)";
    private static final String LEGACY_NO_STATE_PLACEHOLDER = "__NO_STATE__";

    private final ServicesCatalogRepository servicesCatalogRepository;
    private final StateTransitionQueryRepository repository;

    public StateTransitionQueryResult runQuery(StateTransitionQueryRequest request) {
        validate(request);
        UUID serviceId = servicesCatalogRepository.findIdByServiceKey(request.serviceKey());
        if (serviceId == null) {
            throw new IllegalArgumentException("Unknown service key: " + request.serviceKey());
        }

        Duration requestedInterval = request.interval() != null
                ? DurationParser.parse(request.interval())
                : CounterGranularity.S5.duration();
        CounterBucket bucket = resolveBucket(requestedInterval);

        Instant defaultEnd = Instant.now();
        Instant earliestData = repository.findEarliestTimestamp(serviceId, bucket);
        Instant defaultStart =
                earliestData != null ? bucket.align(earliestData) : bucket.align(defaultEnd.minus(Duration.ofDays(14)));

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

        int offset = request.limits() != null && request.limits().offset() != null
                ? request.limits().offset()
                : 0;
        int limit = request.limits() != null && request.limits().limit() != null
                ? request.limits().limit()
                : Integer.MAX_VALUE;

        List<StateTransitionQueryWindow> windows = new ArrayList<>();
        Duration step = requestedInterval;
        Instant cursor = alignedStart.plus(step.multipliedBy(offset));
        int added = 0;

        List<String> fromFilter = normalizeFilter(request.fromStates());
        List<String> toFilter = normalizeFilter(request.toStates());

        while (cursor.isBefore(alignedEnd) && added < limit) {
            Instant next = cursor.plus(step);
            List<StateTransitionQueryRepository.Row> rows = repository.fetchRange(serviceId, bucket, cursor, next);

            List<StateTransitionQueryWindow.Entry> entries = rows.stream()
                    .filter(row -> matches(row.fromState(), fromFilter) && matches(row.toState(), toFilter))
                    .map(row -> new StateTransitionQueryWindow.Entry(
                            renderState(row.fromState()), renderState(row.toState()), row.total()))
                    .collect(Collectors.toList());

            windows.add(new StateTransitionQueryWindow(ISO_INSTANT.format(cursor), ISO_INSTANT.format(next), entries));
            cursor = next;
            added++;
        }

        return new StateTransitionQueryResult(
                windows, offset, limit, computeTotalIntervals(alignedStart, alignedEnd, step), start, end);
    }

    private void validate(StateTransitionQueryRequest request) {
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

    private CounterBucket resolveBucket(Duration requested) {
        for (CounterBucket candidate : CounterBucket.valuesSortedByAscendingDuration()) {
            if (!EnumSet.of(
                            CounterBucket.S5,
                            CounterBucket.M1,
                            CounterBucket.M5,
                            CounterBucket.H1,
                            CounterBucket.D1,
                            CounterBucket.D7)
                    .contains(candidate)) {
                continue;
            }
            if (requested.toMillis() % candidate.duration().toMillis() == 0) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("No compatible bucket for interval " + requested);
    }

    private String renderState(String value) {
        if (value == null) {
            return null;
        }
        if (LEGACY_NO_STATE_PLACEHOLDER.equals(value)) {
            return NO_STATE_LABEL;
        }
        return value;
    }

    private boolean matches(String value, List<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        for (String token : filter) {
            if (WILDCARD.equals(token)) {
                return true;
            }
            if (NO_STATE_LABEL.equals(token)) {
                if (value == null
                        || LEGACY_NO_STATE_PLACEHOLDER.equals(value)
                        || NO_STATE_LABEL.equalsIgnoreCase(value)) {
                    return true;
                }
                continue;
            }
            if (token.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> normalizeFilter(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : values) {
            if (raw == null) {
                continue;
            }
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (WILDCARD.equals(token)) {
                normalized.add(WILDCARD);
                continue;
            }
            if (NO_STATE_LABEL.equalsIgnoreCase(token)
                    || "NONE".equalsIgnoreCase(token)
                    || LEGACY_NO_STATE_PLACEHOLDER.equalsIgnoreCase(token)) {
                normalized.add(NO_STATE_LABEL);
                continue;
            }
            normalized.add(token);
        }
        return normalized;
    }

    private int computeTotalIntervals(Instant start, Instant end, Duration step) {
        long millis = Duration.between(start, end).toMillis();
        long stepMillis = step.toMillis();
        return (int) Math.max(0, millis / stepMillis);
    }
}

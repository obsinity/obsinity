package com.obsinity.service.core.counter;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.CounterConfig;
import com.obsinity.service.core.config.EventTypeConfig;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CounterQueryService {

    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;

    private final ConfigLookup configLookup;
    private final ServicesCatalogRepository servicesCatalogRepository;
    private final CounterQueryRepository repository;
    private final CounterHashService hashService;

    public CounterQueryResult runQuery(CounterQueryRequest request) {
        Objects.requireNonNull(request, "query request");
        if (request.serviceKey() == null || request.serviceKey().isBlank()) {
            throw new IllegalArgumentException("serviceKey is required");
        }
        if (request.eventType() == null || request.eventType().isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (request.counterName() == null || request.counterName().isBlank()) {
            throw new IllegalArgumentException("counterName is required");
        }

        UUID serviceId = servicesCatalogRepository.findIdByServiceKey(request.serviceKey());
        if (serviceId == null) {
            throw new IllegalArgumentException("Unknown service key: " + request.serviceKey());
        }

        EventTypeConfig eventConfig = configLookup
                .get(serviceId, request.eventType())
                .orElseThrow(() -> new IllegalArgumentException("Unknown event type: " + request.eventType()));

        List<CounterConfig> counters = eventConfig.counters() != null ? eventConfig.counters() : List.of();
        CounterConfig counterConfig = counters.stream()
                .filter(c -> c.name().equalsIgnoreCase(request.counterName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown counter: " + request.counterName() + " for event " + request.eventType()));

        CounterGranularity granularity = counterConfig.granularity();
        Duration requestedInterval =
                request.interval() != null ? DurationParser.parse(request.interval()) : granularity.duration();
        if (requestedInterval.compareTo(granularity.duration()) < 0) {
            throw new IllegalArgumentException(
                    "Requested interval " + requestedInterval + " is finer than counter granularity " + granularity);
        }

        CounterBucket bucket = resolveBucket(granularity, requestedInterval);

        Instant defaultEnd = Instant.now();
        Instant earliestData = repository.findEarliestTimestamp(counterConfig.id(), bucket);
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
        Instant alignedEnd = bucket.alignToNext(end.plusMillis(1));

        List<Map<String, String>> keyMatrix = expandKeys(counterConfig.keyedKeys(), request.key());
        List<String> hashes =
                keyMatrix.stream().map(hashService::getOrCreateHash).collect(Collectors.toList());
        String[] hashArray = hashes.toArray(String[]::new);

        int offset = request.limits() != null && request.limits().offset() != null
                ? request.limits().offset()
                : 0;
        int limit = request.limits() != null && request.limits().limit() != null
                ? request.limits().limit()
                : Integer.MAX_VALUE;

        List<CounterQueryWindow> windows = new ArrayList<>();
        Duration step = requestedInterval;
        Instant cursor = alignedStart.plus(step.multipliedBy(offset));
        int intervalsAdded = 0;

        while (cursor.isBefore(alignedEnd) && intervalsAdded < limit) {
            Instant next = cursor.plus(step);
            List<CounterQueryRepository.KeyTotal> totals =
                    repository.fetchRange(counterConfig.id(), bucket, hashArray, cursor, next);
            Map<String, Long> totalsByHash = totals.stream()
                    .collect(Collectors.toMap(
                            CounterQueryRepository.KeyTotal::keyHash, CounterQueryRepository.KeyTotal::total));

            List<CounterQueryWindow.CountEntry> countEntries = new ArrayList<>();
            for (int i = 0; i < keyMatrix.size(); i++) {
                Map<String, String> key = keyMatrix.get(i);
                String hash = hashes.get(i);
                long value = totalsByHash.getOrDefault(hash, 0L);
                countEntries.add(new CounterQueryWindow.CountEntry(key, value));
            }

            windows.add(new CounterQueryWindow(ISO_INSTANT.format(cursor), ISO_INSTANT.format(next), countEntries));
            cursor = next;
            intervalsAdded++;
        }

        return new CounterQueryResult(
                windows, offset, limit, computeTotalIntervals(alignedStart, alignedEnd, step), start, end);
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
            // treat as wildcard - return empty map to produce hash covering key without specific value
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
}

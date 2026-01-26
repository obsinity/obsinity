package com.obsinity.service.core.state.query;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.FromMode;
import com.obsinity.service.core.config.TransitionCounterDefinition;
import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.DurationParser;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransitionRatioQueryService {

    private final ServicesCatalogRepository servicesCatalogRepository;
    private final ConfigLookup configLookup;
    private final TransitionResolvedRollupQueryRepository repository;

    public TransitionRatioQueryResult runQuery(TransitionRatioQueryRequest request) {
        validate(request);

        UUID serviceId = servicesCatalogRepository.findIdByServiceKey(request.serviceKey());
        if (serviceId == null) {
            throw new IllegalArgumentException("Unknown service key: " + request.serviceKey());
        }

        Instant start = Instant.parse(request.start());
        Instant end = Instant.parse(request.end());
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("The requested end time must be after start");
        }

        Duration requestedInterval = DurationParser.parse(request.interval());
        CounterBucket bucket = resolveBucket(requestedInterval);
        boolean groupByFromState = Boolean.TRUE.equals(request.groupByFromState());

        TransitionResolvedRollupQueryService.ResolvedTransitionSummary summary =
                resolveSummary(serviceId, request, bucket, start, end, groupByFromState);

        List<TransitionRatioQueryResult.TransitionRatioEntry> entries = summary.transitions().stream()
                .map(entry -> new TransitionRatioQueryResult.TransitionRatioEntry(
                        entry.fromState(), entry.toState(), entry.count(), entry.ratio()))
                .toList();

        return new TransitionRatioQueryResult(summary.totalCount(), entries);
    }

    private TransitionResolvedRollupQueryService.ResolvedTransitionSummary resolveSummary(
            UUID serviceId,
            TransitionRatioQueryRequest request,
            CounterBucket bucket,
            Instant start,
            Instant end,
            boolean groupByFromState) {
        return inferCountersSummary(serviceId, request, bucket, start, end, groupByFromState);
    }

    private void validate(TransitionRatioQueryRequest request) {
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
        if (request.interval() == null || request.interval().isBlank()) {
            throw new IllegalArgumentException("interval is required");
        }
        if (request.start() == null || request.start().isBlank()) {
            throw new IllegalArgumentException("start is required");
        }
        if (request.end() == null || request.end().isBlank()) {
            throw new IllegalArgumentException("end is required");
        }
        if (request.transitions() == null || request.transitions().isEmpty()) {
            throw new IllegalArgumentException("transitions is required");
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

    private TransitionResolvedRollupQueryService.ResolvedTransitionSummary inferCountersSummary(
            UUID serviceId,
            TransitionRatioQueryRequest request,
            CounterBucket bucket,
            Instant start,
            Instant end,
            boolean groupByFromState) {
        List<FromToKey> pairs = expandPairs(request.transitions());
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("transitions must include at least one from/to pair");
        }

        Map<FromToKey, String> counterIndex =
                indexCounters(configLookup.transitionCounters(serviceId, request.objectType()));
        List<TransitionResolvedRollupQueryService.ResolvedTransitionCount> transitions = new ArrayList<>();
        List<MissingTransitionCountersException.MissingPair> missing = new ArrayList<>();
        long total = 0L;
        for (FromToKey pair : pairs) {
            String counterName = counterIndex.get(pair);
            if (counterName == null) {
                missing.add(new MissingTransitionCountersException.MissingPair(pair.fromState, pair.toState));
                continue;
            }
            long count = repository.sumCounter(
                    serviceId,
                    request.objectType(),
                    request.attribute(),
                    counterName,
                    pair.fromState,
                    pair.toState,
                    bucket,
                    start,
                    end);
            transitions.add(new TransitionResolvedRollupQueryService.ResolvedTransitionCount(
                    pair.fromState, pair.toState, count, 0.0));
            total += count;
        }
        if (!missing.isEmpty()) {
            throw new MissingTransitionCountersException(missing);
        }
        return applyRatios(transitions, total, groupByFromState);
    }

    private Map<FromToKey, String> indexCounters(List<TransitionCounterDefinition> counters) {
        Map<FromToKey, String> index = new LinkedHashMap<>();
        if (counters == null || counters.isEmpty()) {
            return index;
        }
        for (TransitionCounterDefinition counter : counters) {
            if (counter == null || counter.name() == null || counter.toState() == null) {
                continue;
            }
            if (counter.fromMode() != FromMode.SUBSET) {
                continue;
            }
            List<String> fromStates = counter.fromStates();
            if (fromStates == null || fromStates.isEmpty()) {
                continue;
            }
            for (String fromState : fromStates) {
                FromToKey key = new FromToKey(fromState, counter.toState());
                String existing = index.get(key);
                if (existing != null && !existing.equals(counter.name())) {
                    throw new IllegalArgumentException(
                            "Multiple transition counters configured for " + fromState + " -> " + counter.toState());
                }
                index.put(key, counter.name());
            }
        }
        return index;
    }

    private List<FromToKey> expandPairs(List<TransitionRatioQueryRequest.TransitionSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        Map<FromToKey, Boolean> seen = new LinkedHashMap<>();
        for (TransitionRatioQueryRequest.TransitionSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            List<String> fromStates = normalizeStates(spec.from(), true);
            List<String> toStates = normalizeStates(spec.to(), false);
            if (fromStates.isEmpty() || toStates.isEmpty()) {
                continue;
            }
            for (String fromState : fromStates) {
                for (String toState : toStates) {
                    seen.put(new FromToKey(fromState, toState), Boolean.TRUE);
                }
            }
        }
        return new ArrayList<>(seen.keySet());
    }

    private List<String> normalizeStates(List<String> values, boolean allowNulls) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : values) {
            if (raw == null) {
                if (allowNulls) {
                    normalized.add(null);
                }
                continue;
            }
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            normalized.add(token);
        }
        return normalized;
    }

    private TransitionResolvedRollupQueryService.ResolvedTransitionSummary applyRatios(
            List<TransitionResolvedRollupQueryService.ResolvedTransitionCount> transitions,
            long total,
            boolean groupByFromState) {
        if (transitions.isEmpty()) {
            return new TransitionResolvedRollupQueryService.ResolvedTransitionSummary(0L, List.of());
        }
        List<TransitionResolvedRollupQueryService.ResolvedTransitionCount> adjusted =
                new ArrayList<>(transitions.size());
        Map<String, Long> groupTotals = null;
        if (groupByFromState) {
            groupTotals = new LinkedHashMap<>();
            for (TransitionResolvedRollupQueryService.ResolvedTransitionCount entry : transitions) {
                groupTotals.merge(entry.fromState(), entry.count(), Long::sum);
            }
        }
        for (TransitionResolvedRollupQueryService.ResolvedTransitionCount entry : transitions) {
            long denom = groupByFromState ? groupTotals.getOrDefault(entry.fromState(), 0L) : total;
            double ratio = denom > 0 ? (double) entry.count() / (double) denom : 0.0;
            adjusted.add(new TransitionResolvedRollupQueryService.ResolvedTransitionCount(
                    entry.fromState(), entry.toState(), entry.count(), ratio));
        }
        return new TransitionResolvedRollupQueryService.ResolvedTransitionSummary(total, List.copyOf(adjusted));
    }

    private record FromToKey(String fromState, String toState) {}
}

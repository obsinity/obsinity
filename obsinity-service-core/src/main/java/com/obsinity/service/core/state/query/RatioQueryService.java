package com.obsinity.service.core.state.query;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.RatioQueryDefinition;
import com.obsinity.service.core.config.RatioQueryDefinition.Behavior;
import com.obsinity.service.core.config.RatioQueryDefinition.Item;
import com.obsinity.service.core.config.RatioQueryDefinition.Source;
import com.obsinity.service.core.config.RatioQueryDefinition.ValueMode;
import com.obsinity.service.core.config.RatioQueryDefinition.ZeroTotalBehavior;
import com.obsinity.service.core.counter.CounterBucket;
import com.obsinity.service.core.counter.DurationParser;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RatioQueryService {
    private static final Logger log = LoggerFactory.getLogger(RatioQueryService.class);

    private final ServicesCatalogRepository servicesCatalogRepository;
    private final ConfigLookup configLookup;
    private final StateCountTimeseriesQueryRepository stateCountTimeseriesQueryRepository;
    private final StateTransitionQueryRepository stateTransitionQueryRepository;

    public RatioQueryService(
            ServicesCatalogRepository servicesCatalogRepository,
            ConfigLookup configLookup,
            StateCountTimeseriesQueryRepository stateCountTimeseriesQueryRepository,
            StateTransitionQueryRepository stateTransitionQueryRepository) {
        this.servicesCatalogRepository = servicesCatalogRepository;
        this.configLookup = configLookup;
        this.stateCountTimeseriesQueryRepository = stateCountTimeseriesQueryRepository;
        this.stateTransitionQueryRepository = stateTransitionQueryRepository;
    }

    public RatioQueryResult runQuery(RatioQueryRequest request) {
        validate(request);
        UUID serviceId = servicesCatalogRepository.findIdByServiceKey(request.serviceKey());
        if (serviceId == null) {
            throw new IllegalArgumentException("Unknown service key: " + request.serviceKey());
        }
        RatioQueryDefinition definition = configLookup
                .ratioQuery(serviceId, request.name())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown ratio query '" + request.name() + "' for service " + request.serviceKey()));

        return executeQuery(serviceId, request.serviceKey(), request.from(), request.to(), definition, false);
    }

    public RatioQueryResult runAdHocQuery(AdHocRatioQueryRequest request) {
        validateAdHoc(request);
        UUID serviceId = servicesCatalogRepository.findIdByServiceKey(request.serviceKey());
        if (serviceId == null) {
            throw new IllegalArgumentException("Unknown service key: " + request.serviceKey());
        }
        RatioQueryDefinition definition = buildAdHocDefinition(request);
        return executeQuery(
                serviceId,
                request.serviceKey(),
                request.from(),
                request.to(),
                definition,
                Boolean.TRUE.equals(request.latestMinute()));
    }

    private RatioQueryResult executeQuery(
            UUID serviceId,
            String serviceKey,
            String requestedFrom,
            String requestedTo,
            RatioQueryDefinition definition,
            boolean latestMinuteTransitions) {
        Instant now = Instant.now();
        Instant from = resolveTime(requestedFrom, definition.window().from(), now, true);
        Instant to = resolveTime(requestedTo, definition.window().to(), now, false);
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("ratio query window must have to > from");
        }

        Map<Item, Long> counts = resolveCounts(serviceId, definition, from, to, latestMinuteTransitions);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0L) {
            if (definition.behavior().zeroTotal() == ZeroTotalBehavior.EMPTY) {
                return new RatioQueryResult(definition.name(), serviceKey, from, to, 0L, List.of());
            }
            if (definition.behavior().zeroTotal() == ZeroTotalBehavior.ERROR) {
                throw new IllegalArgumentException("ratio query total is zero for '" + definition.name() + "'");
            }
        }

        List<RatioQueryResult.RatioSlice> slices =
                new ArrayList<>(definition.items().size());
        for (Item item : definition.items()) {
            long raw = counts.getOrDefault(item, 0L);
            double ratio = total == 0L ? 0.0d : ((double) raw) / (double) total;
            double percent = ratio * 100.0d;
            double roundedRatio = round(ratio, definition.output().decimals());
            double roundedPercent = round(percent, definition.output().decimals());
            Number value = toValue(definition.output().valueMode(), raw, roundedPercent, roundedRatio);
            slices.add(new RatioQueryResult.RatioSlice(
                    item.label(),
                    value,
                    definition.output().includeRatio() ? roundedRatio : null,
                    definition.output().includePercent() ? roundedPercent : null,
                    definition.output().includeRaw() ? raw : null));
        }
        return new RatioQueryResult(definition.name(), serviceKey, from, to, total, List.copyOf(slices));
    }

    private RatioQueryDefinition buildAdHocDefinition(AdHocRatioQueryRequest request) {
        String queryName = request.name() == null || request.name().isBlank()
                ? "adhoc_ratio_query"
                : request.name().trim();
        RatioQueryDefinition.Source source = RatioQueryDefinition.Source.fromConfigValue(request.source());
        RatioQueryDefinition.Output output = new RatioQueryDefinition.Output(
                RatioQueryDefinition.OutputFormat.GRAFANA_PIE,
                RatioQueryDefinition.ValueMode.fromConfigValue(request.value()),
                request.includeRaw() == null || request.includeRaw(),
                request.includePercent() == null || request.includePercent(),
                request.includeRatio() == null || request.includeRatio(),
                request.decimals() == null ? 2 : Math.max(0, request.decimals()));
        RatioQueryDefinition.Behavior behavior = new RatioQueryDefinition.Behavior(
                RatioQueryDefinition.ZeroTotalBehavior.fromConfigValue(request.zeroTotal()),
                RatioQueryDefinition.MissingItemBehavior.fromConfigValue(request.missingItem()));
        List<RatioQueryDefinition.Item> items = materializeAdHocItems(queryName, source, request.items());
        return new RatioQueryDefinition(
                queryName,
                source,
                request.objectType().trim(),
                request.attribute().trim(),
                new RatioQueryDefinition.Window("-15m", "now"),
                items,
                output,
                behavior);
    }

    private List<RatioQueryDefinition.Item> materializeAdHocItems(
            String queryName, RatioQueryDefinition.Source source, List<AdHocRatioQueryRequest.Item> items) {
        List<RatioQueryDefinition.Item> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            AdHocRatioQueryRequest.Item item = items.get(i);
            if (item == null) {
                continue;
            }
            String state = trimToNull(item.state());
            String transition = trimToNull(item.transition());
            String label = trimToNull(item.label());
            if (source == RatioQueryDefinition.Source.STATES && state == null) {
                throw new IllegalArgumentException(
                        "ratio query '" + queryName + "' item " + i + " must provide state for source=states");
            }
            if (source == RatioQueryDefinition.Source.TRANSITIONS && transition == null) {
                throw new IllegalArgumentException("ratio query '" + queryName + "' item " + i
                        + " must provide transition for source=transitions");
            }
            if (source == RatioQueryDefinition.Source.MIXED) {
                boolean hasState = state != null;
                boolean hasTransition = transition != null;
                if (hasState == hasTransition) {
                    throw new IllegalArgumentException("ratio query '" + queryName + "' item " + i
                            + " must provide exactly one of state or transition for source=mixed");
                }
            }
            if (state != null && transition != null) {
                throw new IllegalArgumentException(
                        "ratio query '" + queryName + "' item " + i + " cannot set both state and transition");
            }
            out.add(new RatioQueryDefinition.Item(
                    state, transition, label == null ? (state != null ? state : transition) : label));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("ratio query items must be non-empty");
        }
        return List.copyOf(out);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateAdHoc(AdHocRatioQueryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ratio query request is required");
        }
        if (request.serviceKey() == null || request.serviceKey().isBlank()) {
            throw new IllegalArgumentException("serviceKey is required");
        }
        if (request.objectType() == null || request.objectType().isBlank()) {
            throw new IllegalArgumentException("objectType is required");
        }
        if (request.attribute() == null || request.attribute().isBlank()) {
            throw new IllegalArgumentException("attribute is required");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("items must be non-empty");
        }
    }

    private Map<Item, Long> resolveCounts(
            UUID serviceId,
            RatioQueryDefinition definition,
            Instant from,
            Instant to,
            boolean latestMinuteTransitions) {
        Behavior behavior = definition.behavior();
        Map<Item, Long> resolved = new LinkedHashMap<>();
        Map<String, Long> states = Map.of();
        Map<StateTransitionQueryRepository.TransitionKey, Long> transitions = Map.of();

        if (definition.source() == Source.STATES || definition.source() == Source.MIXED) {
            List<String> statesToFetch = definition.items().stream()
                    .map(Item::state)
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .toList();
            states = stateCountTimeseriesQueryRepository.fetchLatestCountsInRange(
                    serviceId,
                    definition.objectType(),
                    definition.attribute(),
                    statesToFetch,
                    CounterBucket.M1,
                    from,
                    to);
        }

        if (definition.source() == Source.TRANSITIONS || definition.source() == Source.MIXED) {
            List<StateTransitionQueryRepository.TransitionKey> transitionsToFetch = new ArrayList<>();
            for (String transition : definition.items().stream()
                    .map(Item::transition)
                    .filter(t -> t != null && !t.isBlank())
                    .toList()) {
                transitionsToFetch.add(parseTransition(transition));
            }
            Instant transitionFrom = from;
            Instant transitionTo = to;
            if (latestMinuteTransitions) {
                Instant latestTs = stateTransitionQueryRepository.findLatestTimestampInRange(
                        serviceId, definition.objectType(), definition.attribute(), CounterBucket.M1, from, to);
                if (latestTs == null) {
                    transitionFrom = null;
                    transitionTo = null;
                } else {
                    transitionFrom = latestTs;
                    transitionTo = latestTs.plus(CounterBucket.M1.duration());
                }
            }
            if (transitionFrom != null && transitionTo != null) {
                transitions = stateTransitionQueryRepository.sumTransitions(
                        serviceId,
                        definition.objectType(),
                        definition.attribute(),
                        CounterBucket.M1,
                        transitionFrom,
                        transitionTo,
                        dedupeTransitions(transitionsToFetch));
            }
        }

        List<String> missing = new ArrayList<>();
        for (Item item : definition.items()) {
            long raw = 0L;
            if (item.state() != null) {
                Optional<Long> value = Optional.ofNullable(states.get(item.state()));
                if (value.isPresent()) {
                    raw = value.get();
                } else if (behavior.missingItem() == RatioQueryDefinition.MissingItemBehavior.ERROR) {
                    missing.add("state:" + item.state());
                }
            } else if (item.transition() != null) {
                StateTransitionQueryRepository.TransitionKey key = parseTransition(item.transition());
                Optional<Long> value = Optional.ofNullable(transitions.get(key));
                if (value.isPresent()) {
                    raw = value.get();
                } else if (behavior.missingItem() == RatioQueryDefinition.MissingItemBehavior.ERROR) {
                    missing.add("transition:" + item.transition());
                }
            }
            resolved.put(item, raw);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing ratio query items: " + String.join(", ", missing));
        }
        return resolved;
    }

    private List<StateTransitionQueryRepository.TransitionKey> dedupeTransitions(
            List<StateTransitionQueryRepository.TransitionKey> transitions) {
        if (transitions.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<StateTransitionQueryRepository.TransitionKey> deduped = new LinkedHashSet<>(transitions);
        return List.copyOf(deduped);
    }

    private StateTransitionQueryRepository.TransitionKey parseTransition(String token) {
        int arrow = token.indexOf("->");
        if (arrow <= 0 || arrow >= token.length() - 2) {
            throw new IllegalArgumentException("Invalid transition token '" + token + "'. Expected from->to");
        }
        String from = token.substring(0, arrow).trim();
        String to = token.substring(arrow + 2).trim();
        if (from.isEmpty() || to.isEmpty()) {
            throw new IllegalArgumentException("Invalid transition token '" + token + "'. Expected from->to");
        }
        return new StateTransitionQueryRepository.TransitionKey(from, to);
    }

    private Number toValue(ValueMode mode, long raw, double percent, double ratio) {
        return switch (mode) {
            case COUNT -> raw;
            case PERCENT -> percent;
            case RATIO -> ratio;
        };
    }

    private double round(double value, int decimals) {
        return BigDecimal.valueOf(value)
                .setScale(Math.max(0, decimals), RoundingMode.HALF_UP)
                .doubleValue();
    }

    private Instant resolveTime(String requestValue, String configuredValue, Instant now, boolean from) {
        String candidate = normalizeTimeValue(requestValue != null ? requestValue : configuredValue, from);
        if ("now".equalsIgnoreCase(candidate)) {
            return now;
        }
        if (candidate.startsWith("now-")) {
            Duration delta = DurationParser.parse(candidate.substring(4));
            return now.minus(delta);
        }
        if (candidate.startsWith("-")) {
            Duration delta = DurationParser.parse(candidate);
            return now.plus(delta);
        }
        return Instant.parse(candidate);
    }

    private String normalizeTimeValue(String value, boolean from) {
        if (value == null || value.isBlank()) {
            return from ? "-15m" : "now";
        }
        return value.trim();
    }

    private void validate(RatioQueryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ratio query request is required");
        }
        if (request.serviceKey() == null || request.serviceKey().isBlank()) {
            throw new IllegalArgumentException("serviceKey is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (request.from() != null && request.from().isBlank()) {
            throw new IllegalArgumentException("from cannot be blank");
        }
        if (request.to() != null && request.to().isBlank()) {
            throw new IllegalArgumentException("to cannot be blank");
        }
        if (request.name().trim().toLowerCase(Locale.ROOT).contains(" ")) {
            log.debug("ratio query name contains spaces: '{}'", request.name());
        }
    }
}

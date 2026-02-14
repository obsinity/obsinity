package com.obsinity.reference.demodata;

import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class DbDrivenProfileGenerator {

    private final DemoProfileGeneratorProperties properties;
    private final ProfileGeneratorRepository repository;
    private final RandomProvider randomProvider;
    private final EventIngestService ingestService;
    private final Clock clock;

    @Autowired
    DbDrivenProfileGenerator(
            DemoProfileGeneratorProperties properties,
            ProfileGeneratorRepository repository,
            RandomProvider randomProvider,
            EventIngestService ingestService) {
        this(properties, repository, randomProvider, ingestService, Clock.systemUTC());
    }

    DbDrivenProfileGenerator(
            DemoProfileGeneratorProperties properties,
            ProfileGeneratorRepository repository,
            RandomProvider randomProvider,
            EventIngestService ingestService,
            Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.randomProvider = randomProvider;
        this.ingestService = ingestService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "#{@demoDataProfileGeneratorProperties.runEvery.toMillis()}")
    public void scheduledRun() {
        runOnce();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startupRun() {
        runOnce();
    }

    void runOnce() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        long runEverySeconds = Math.max(1L, properties.getRunEvery().toSeconds());
        Random runRandom = randomProvider.forRun(now, runEverySeconds);

        int created = fillToTarget(now);
        int transitioned = transitionProfiles(now, runRandom);

        if (created > 0 || transitioned > 0) {
            log.info("Demo profile generator run complete created={} transitioned={}", created, transitioned);
        }
    }

    private int fillToTarget(Instant now) {
        int createPerRun = properties.getCreatePerRun();
        if (createPerRun <= 0) {
            return 0;
        }

        int toCreate = createPerRun;
        int target = properties.getTargetCount();
        if (target > 0) {
            long existing = repository.countProfiles();
            long delta = target - existing;
            if (delta <= 0) {
                return 0;
            }
            toCreate = (int) Math.min(delta, createPerRun);
        }

        List<UUID> ids = new ArrayList<>(toCreate);
        for (int i = 0; i < toCreate; i++) {
            ids.add(UUID.randomUUID());
        }
        String initialState = safeState(properties.getInitialState());
        int inserted = repository.insertProfiles(ids, initialState, now);
        if (inserted > 0) {
            emitStateEvents(ids, initialState, now);
        }
        return inserted;
    }

    private int transitionProfiles(Instant now, Random random) {
        Map<String, DemoProfileGeneratorProperties.TransitionRule> rules = properties.getTransitions();
        if (rules == null || rules.isEmpty()) {
            return 0;
        }

        int updated = 0;
        Set<UUID> updatedInRun = new HashSet<>();
        List<TransitionApplied> applied = new ArrayList<>();

        List<String> fromStates = rules.keySet().stream().sorted().collect(Collectors.toList());
        for (String fromState : fromStates) {
            DemoProfileGeneratorProperties.TransitionRule rule = rules.get(fromState);
            if (rule == null || rule.getNext() == null || rule.getNext().isEmpty()) {
                continue;
            }
            WeightedNextStateSelector selector = buildSelector(rule, random);
            DemoProfileGeneratorProperties.Selection selection =
                    rule.getSelect() == null ? new DemoProfileGeneratorProperties.Selection() : rule.getSelect();
            int limitPerRun = Math.max(0, selection.getLimitPerRun());
            if (limitPerRun <= 0) {
                continue;
            }

            Duration minAge = selection.getMinAge() == null ? Duration.ZERO : selection.getMinAge();
            Duration maxAge = selection.getMaxAge();
            Instant upperBound = now.minus(minAge);
            Instant lowerBound = maxAge == null ? null : now.minus(maxAge);
            if (lowerBound != null && lowerBound.isAfter(upperBound)) {
                continue;
            }

            int oversample = Math.min(20, Math.max(1, properties.getOversampleFactor()));
            int maxSelection = Math.max(limitPerRun, properties.getMaxSelectionPerState());
            int queryLimit = Math.min(maxSelection, limitPerRun * oversample);

            List<ProfileCandidate> candidates =
                    repository.selectCandidates(safeState(fromState), upperBound, lowerBound, queryLimit);
            if (candidates.isEmpty()) {
                continue;
            }

            Collections.shuffle(candidates, random);
            int transitionsForState = 0;

            for (ProfileCandidate candidate : candidates) {
                if (transitionsForState >= limitPerRun) {
                    break;
                }
                if (!updatedInRun.add(candidate.id())) {
                    continue;
                }

                String toState = selector.pick(random);
                int rows = repository.casUpdateState(
                        candidate.id(), safeState(fromState), candidate.stateChangedAt(), toState, now);
                if (rows > 0) {
                    transitionsForState++;
                    updated++;
                    applied.add(new TransitionApplied(candidate.id(), toState));
                }
            }
        }
        if (!applied.isEmpty()) {
            emitTransitionEvents(applied, now);
        }

        return updated;
    }

    private WeightedNextStateSelector buildSelector(DemoProfileGeneratorProperties.TransitionRule rule, Random random) {
        List<String> states = rule.getNext().stream().map(this::safeState).toList();
        if (states.isEmpty()) {
            return new WeightedNextStateSelector(List.of("NEW"), new double[] {1.0});
        }

        Map<String, Integer> configuredWeights = rule.getWeights() == null ? Map.of() : rule.getWeights();
        double jitter = Math.max(0d, Math.min(1d, rule.getRunJitterPercent() / 100.0d));
        double[] cumulative = new double[states.size()];
        double total = 0d;
        for (int i = 0; i < states.size(); i++) {
            String state = states.get(i);
            int baseWeight = Math.max(1, configuredWeights.getOrDefault(state, 1));
            double factor = jitter == 0d ? 1d : (1d - jitter) + (random.nextDouble() * (2d * jitter));
            total += Math.max(0.0001d, baseWeight * factor);
            cumulative[i] = total;
        }
        return new WeightedNextStateSelector(states, cumulative);
    }

    private String safeState(String state) {
        if (state == null || state.isBlank()) {
            return "NEW";
        }
        return state.trim();
    }

    private void emitStateEvents(List<UUID> ids, String state, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<EventEnvelope> batch = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            batch.add(toEvent(id, state, now));
        }
        ingestService.ingestBatch(batch);
    }

    private void emitTransitionEvents(List<TransitionApplied> applied, Instant now) {
        List<EventEnvelope> batch = new ArrayList<>(applied.size());
        for (TransitionApplied transition : applied) {
            batch.add(toEvent(transition.profileId(), transition.state(), now));
        }
        ingestService.ingestBatch(batch);
    }

    private EventEnvelope toEvent(UUID profileId, String state, Instant now) {
        String serviceKey = safeService(properties.getServiceKey(), "payments");
        String eventType = safeService(properties.getEventType(), "user_profile.updated");
        return EventEnvelope.builder()
                .serviceId(serviceKey)
                .eventType(eventType)
                .eventId(UUID.randomUUID().toString())
                .timestamp(now)
                .ingestedAt(now)
                .name(eventType)
                .attributes(Map.of("user", Map.of("profile_id", profileId.toString(), "status", state)))
                .resourceAttributes(Map.of())
                .build();
    }

    private String safeService(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private record TransitionApplied(UUID profileId, String state) {}

    private record WeightedNextStateSelector(List<String> states, double[] cumulative) {
        String pick(Random random) {
            if (states.size() == 1) {
                return states.get(0);
            }
            double max = cumulative[cumulative.length - 1];
            double roll = random.nextDouble() * max;
            for (int i = 0; i < cumulative.length; i++) {
                if (roll <= cumulative[i]) {
                    return states.get(i);
                }
            }
            return states.get(states.size() - 1);
        }
    }
}

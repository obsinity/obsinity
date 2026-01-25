package com.obsinity.service.core.state.transition.counter;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.FromMode;
import com.obsinity.service.core.config.TransitionCounterDefinition;
import com.obsinity.service.core.state.transition.codec.StateCodec;
import com.obsinity.service.core.state.transition.telemetry.TransitionTelemetry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransitionCounterEvaluator {
    private static final Logger log = LoggerFactory.getLogger(TransitionCounterEvaluator.class);
    private final ConfigLookup configLookup;
    private final TransitionCounterSnapshotStore snapshotStore;
    private final TransitionCounterPostingSink postingSink;
    private final TerminalStateResolver terminalStateResolver;
    private final TransitionCounterPostingIdFactory postingIdFactory;
    private final TransitionTelemetry telemetry;
    private final StateCodec stateCodec;

    @org.springframework.beans.factory.annotation.Value("${obsinity.stateTransitions.maxFromStates:0}")
    private int maxFromStates;

    @org.springframework.beans.factory.annotation.Value("${obsinity.stateTransitions.maxSeenStates:0}")
    private int maxSeenStates;

    public void evaluate(
            UUID serviceId,
            String eventId,
            Instant eventTs,
            String objectType,
            String objectId,
            String attribute,
            String newState) {
        evaluate(serviceId, eventId, eventTs, objectType, objectId, attribute, newState, null);
    }

    public void evaluate(
            UUID serviceId,
            String eventId,
            Instant eventTs,
            String objectType,
            String objectId,
            String attribute,
            String newState,
            SyntheticContext syntheticContext) {
        if (serviceId == null
                || eventId == null
                || eventTs == null
                || objectType == null
                || objectId == null
                || attribute == null
                || newState == null) {
            return;
        }
        List<TransitionCounterDefinition> rules = configLookup.transitionCounters(serviceId, objectType);
        if (rules.isEmpty()) {
            return;
        }
        TransitionCounterSnapshot snapshot = snapshotStore.find(serviceId, objectType, objectId, attribute);
        String lastState = snapshot != null ? snapshot.lastState() : null;
        SeenStates seenStates = snapshot != null && snapshot.seenStates() != null
                ? new SeenStates(
                        serviceId, objectType, attribute, snapshot.seenStates().bits())
                : SeenStates.empty(serviceId, objectType, attribute);
        String terminalState = snapshot != null ? snapshot.terminalState() : null;

        boolean sameState = lastState != null && lastState.equals(newState);
        SeenStates seenBefore = new SeenStates(serviceId, objectType, attribute, seenStates.bits());

        boolean terminalReached =
                terminalStateResolver.terminalStates(serviceId, objectType).contains(newState);
        boolean isTerminalAfterEvent = terminalReached || terminalState != null;

        if (!sameState) {
            List<TransitionCounterFootprintEntry> footprint =
                    syntheticContext != null ? new java.util.ArrayList<>() : null;
            for (TransitionCounterDefinition rule : rules) {
                if (rule == null) continue;
                if (!objectType.equals(rule.objectType())) continue;
                emitForRule(
                        rule,
                        serviceId,
                        objectType,
                        attribute,
                        newState,
                        eventId,
                        eventTs,
                        seenBefore,
                        lastState,
                        isTerminalAfterEvent,
                        footprint);
            }
            if (syntheticContext != null && footprint != null && syntheticContext.footprintRecorder() != null) {
                syntheticContext
                        .footprintRecorder()
                        .recordFootprint(syntheticContext.syntheticEventId(), List.copyOf(footprint));
            }
        }

        boolean canAdd = true;
        if (maxSeenStates > 0 && !seenStates.contains(stateCodec, newState) && seenStates.size() >= maxSeenStates) {
            canAdd = false;
            if (telemetry != null) {
                telemetry.recordSeenStatesCapExceeded(objectType, attribute, maxSeenStates);
            }
            log.warn(
                    "Seen states cap reached: objectType={} attribute={} cap={} size={}",
                    objectType,
                    attribute,
                    maxSeenStates,
                    seenStates.size());
        }
        if (canAdd) {
            seenStates.add(stateCodec, newState);
        }
        String updatedTerminal = terminalState != null ? terminalState : (terminalReached ? newState : null);
        snapshotStore.upsert(
                serviceId, objectType, objectId, attribute, newState, seenStates, eventTs, updatedTerminal);
    }

    private void emitForRule(
            TransitionCounterDefinition rule,
            UUID serviceId,
            String objectType,
            String attribute,
            String newState,
            String eventId,
            Instant eventTs,
            SeenStates seenBefore,
            String lastState,
            boolean isTerminalAfterEvent,
            List<TransitionCounterFootprintEntry> footprint) {
        if (rule.toState() != null) {
            if (!rule.toState().equals(newState)) {
                return;
            }
            Set<String> fromStates = fromStates(rule, seenBefore, lastState);
            for (String from : fromStates) {
                TransitionCounterMetricKey key =
                        new TransitionCounterMetricKey(serviceId, objectType, attribute, rule.name(), from, newState);
                String postingId = postingIdFactory.build(eventId, key, 1, eventTs);
                postingSink.post(key, eventTs, 1, postingId);
            }
            if (footprint != null && !fromStates.isEmpty()) {
                footprint.add(new TransitionCounterFootprintEntry(
                        rule.name(), new java.util.ArrayList<>(fromStates), newState));
            }
            return;
        }

        if (rule.untilTerminal()) {
            if (isTerminalAfterEvent) {
                return;
            }
            Set<String> fromStates = fromStates(rule, seenBefore, lastState);
            for (String from : fromStates) {
                TransitionCounterMetricKey key =
                        new TransitionCounterMetricKey(serviceId, objectType, attribute, rule.name(), from, null);
                String postingId = postingIdFactory.build(eventId, key, 1, eventTs);
                postingSink.post(key, eventTs, 1, postingId);
            }
        }
    }

    private Set<String> fromStates(TransitionCounterDefinition rule, SeenStates seenBefore, String lastState) {
        if (rule.fromMode() == null) {
            return Set.of();
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        if (rule.fromMode() == FromMode.DEFAULT_LAST) {
            result.add(lastState);
            return result;
        }
        if (rule.fromMode() == FromMode.ANY_SEEN) {
            if (seenBefore == null || seenBefore.size() == 0) {
                return Set.of();
            }
            return truncateAnySeen(seenBefore, rule);
        }
        if (rule.fromMode() == FromMode.SUBSET) {
            if (rule.fromStates() == null || rule.fromStates().isEmpty() || seenBefore == null) {
                return Set.of();
            }
            for (String state : rule.fromStates()) {
                if (state == null) {
                    if (lastState == null) {
                        result.add(null);
                    }
                    continue;
                }
                if (seenBefore.size() > 0 && seenBefore.contains(stateCodec, state)) {
                    result.add(state);
                }
            }
            return result.isEmpty() ? Set.of() : result;
        }
        return Set.of();
    }

    private Set<String> truncateAnySeen(SeenStates seenBefore, TransitionCounterDefinition rule) {
        if (seenBefore == null || seenBefore.size() == 0 || maxFromStates <= 0) {
            return seenBefore == null ? Set.of() : seenBefore.toSet(stateCodec);
        }
        if (seenBefore.size() <= maxFromStates) {
            return seenBefore.toSet(stateCodec);
        }
        java.util.List<String> decoded = seenBefore.toList(stateCodec);
        java.util.List<String> truncated = decoded.subList(0, Math.min(maxFromStates, decoded.size()));
        if (telemetry != null) {
            telemetry.recordFanoutTruncation(rule.objectType(), rule.name(), seenBefore.size(), truncated.size());
        }
        log.warn(
                "Transition counter ANY_SEEN truncation applied: objectType={} counterName={} original={} truncated={}",
                rule.objectType(),
                rule.name(),
                seenBefore.size(),
                truncated.size());
        return java.util.Set.copyOf(truncated);
    }

    public record SyntheticContext(String syntheticEventId, TransitionCounterFootprintRecorder footprintRecorder) {}
}

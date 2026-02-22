package com.obsinity.service.core.state;

import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.StateExtractorDefinition;
import com.obsinity.service.core.counter.CounterGranularity;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.repo.ObjectStateCountRepository;
import com.obsinity.service.core.repo.StateSnapshotRepository;
import com.obsinity.service.core.state.transition.StateTransitionBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StateDetectionService {

    private static final String NO_STATE_LABEL = "(none)";
    private static final String TRANSITION_FROM_LATEST = "?";
    private static final String TRANSITION_FROM_ALL = "*";

    private final ConfigLookup configLookup;
    private final StateSnapshotRepository snapshotRepository;
    private final ObjectStateCountRepository stateCountRepository;
    private final StateTransitionBuffer transitionBuffer;

    @org.springframework.beans.factory.annotation.Value("${obsinity.stateExtractors.loggingEnabled:true}")
    private boolean loggingEnabled;

    public void process(UUID serviceId, EventEnvelope envelope) {
        if (serviceId == null || envelope == null) {
            return;
        }
        List<StateExtractorDefinition> extractors = configLookup.stateExtractors(serviceId, envelope.getName());
        if (extractors.isEmpty()) {
            return;
        }
        List<StateMatch> matches = detectMatches(extractors, envelope.getAttributes(), envelope.getEventId());
        if (matches.isEmpty()) {
            return;
        }
        long alignedEpoch = CounterGranularity.S5
                .baseBucket()
                .align(envelope.getTimestamp())
                .getEpochSecond();
        for (StateMatch match : matches) {
            match.stateValues().forEach((attr, value) -> {
                String previous = snapshotRepository.findLatest(
                        serviceId, match.extractor().objectType(), match.objectId(), attr);
                if (previous != null && previous.equals(value)) {
                    return;
                }
                if (loggingEnabled) {
                    log.debug(
                            "StateExtractor matched: event={} timestamp={} objectType={} objectId={} attribute={} from={} to={} extractor={}",
                            envelope.getEventId(),
                            envelope.getTimestamp(),
                            match.extractor().objectType(),
                            match.objectId(),
                            attr,
                            previous,
                            value,
                            match.extractor().rawType());
                }
                snapshotRepository.upsert(
                        serviceId,
                        match.extractor().objectType(),
                        match.objectId(),
                        attr,
                        value,
                        envelope.getTimestamp());
                if (previous != null && !previous.isBlank()) {
                    stateCountRepository.decrement(serviceId, match.extractor().objectType(), attr, previous);
                    List<String> fromStates = resolveTransitionFromStates(
                            serviceId, match.extractor(), match.objectId(), attr, previous, value);
                    for (String fromState : fromStates) {
                        transitionBuffer.increment(
                                CounterGranularity.S5,
                                alignedEpoch,
                                serviceId,
                                match.extractor().objectType(),
                                attr,
                                fromState,
                                value);
                    }
                } else {
                    transitionBuffer.increment(
                            CounterGranularity.S5,
                            alignedEpoch,
                            serviceId,
                            match.extractor().objectType(),
                            attr,
                            NO_STATE_LABEL,
                            value);
                }
                stateCountRepository.increment(serviceId, match.extractor().objectType(), attr, value);
            });
        }
    }

    private List<String> resolveTransitionFromStates(
            UUID serviceId,
            StateExtractorDefinition extractor,
            String objectId,
            String attribute,
            String previous,
            String newValue) {
        List<String> onlyConfigured = extractor.transitionOnlyFromStates();
        List<String> additionalConfigured = extractor.transitionAdditionalFromStates();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (onlyConfigured != null && onlyConfigured.contains(TRANSITION_FROM_ALL)) {
            return snapshotRepository
                    .findStateHistoryValues(serviceId, extractor.objectType(), objectId, attribute, 1000)
                    .stream()
                    .filter(state -> state != null && !state.isBlank())
                    .filter(state -> !state.equals(newValue))
                    .toList();
        }

        List<String> history = null;
        boolean restrictiveOnly = onlyConfigured != null && !onlyConfigured.isEmpty();

        if (restrictiveOnly) {
            boolean includeLatest = onlyConfigured.contains(TRANSITION_FROM_LATEST);
            boolean hasExplicit = onlyConfigured.stream().anyMatch(token -> !isControlToken(token));
            if (hasExplicit) {
                history = snapshotRepository.findStateHistoryValues(
                        serviceId, extractor.objectType(), objectId, attribute, 1000);
            }
            if (includeLatest && previous != null && !previous.isBlank() && !previous.equals(newValue)) {
                selected.add(previous);
            }
            if (hasExplicit && history != null && !history.isEmpty()) {
                LinkedHashSet<String> historySet = new LinkedHashSet<>(history);
                for (String token : onlyConfigured) {
                    if (isControlToken(token)) {
                        continue;
                    }
                    if (token != null && !token.isBlank() && !token.equals(newValue) && historySet.contains(token)) {
                        selected.add(token);
                    }
                }
            }
            return selected.stream().toList();
        }

        // Default additive behavior: always include the immediate previous state transition.
        if (previous != null && !previous.isBlank() && !previous.equals(newValue)) {
            selected.add(previous);
        }
        if (additionalConfigured != null && additionalConfigured.contains(TRANSITION_FROM_ALL)) {
            return snapshotRepository
                    .findStateHistoryValues(serviceId, extractor.objectType(), objectId, attribute, 1000)
                    .stream()
                    .filter(state -> state != null && !state.isBlank())
                    .filter(state -> !state.equals(newValue))
                    .distinct()
                    .toList();
        }

        boolean hasAdditionalExplicit =
                additionalConfigured != null && additionalConfigured.stream().anyMatch(token -> !isControlToken(token));
        if (hasAdditionalExplicit) {
            history = snapshotRepository.findStateHistoryValues(
                    serviceId, extractor.objectType(), objectId, attribute, 1000);
            if (history != null && !history.isEmpty()) {
                LinkedHashSet<String> historySet = new LinkedHashSet<>(history);
                for (String token : additionalConfigured) {
                    if (isControlToken(token)) {
                        continue;
                    }
                    if (token != null && !token.isBlank() && !token.equals(newValue) && historySet.contains(token)) {
                        selected.add(token);
                    }
                }
            }
        }
        if (additionalConfigured != null && additionalConfigured.contains(TRANSITION_FROM_LATEST)) {
            if (previous != null && !previous.isBlank() && !previous.equals(newValue)) {
                selected.add(previous);
            }
        }
        return selected.stream().toList();
    }

    private boolean isControlToken(String token) {
        return TRANSITION_FROM_LATEST.equals(token) || TRANSITION_FROM_ALL.equals(token);
    }

    List<StateMatch> detectMatches(
            List<StateExtractorDefinition> extractors, Map<String, Object> attributes, String eventId) {
        if (extractors == null || extractors.isEmpty() || attributes == null || attributes.isEmpty()) {
            return List.of();
        }
        List<StateMatch> matches = new ArrayList<>();
        for (StateExtractorDefinition extractor : extractors) {
            if (extractor == null) continue;
            Object objectIdRaw = resolveAttribute(attributes, extractor.objectIdField());
            if (objectIdRaw == null) {
                continue;
            }
            String objectId = stringify(objectIdRaw);
            if (objectId.isBlank()) continue;
            List<String> attributesToCheck = extractor.stateAttributes();
            if (attributesToCheck == null || attributesToCheck.isEmpty()) {
                continue;
            }
            Map<String, String> stateValues = new LinkedHashMap<>();
            for (String attrPath : attributesToCheck) {
                String path = attrPath == null ? null : attrPath.trim();
                if (path == null || path.isEmpty()) continue;
                Object attrValue = resolveAttribute(attributes, path);
                if (attrValue != null) {
                    stateValues.put(path, stringify(attrValue));
                }
            }
            if (stateValues.isEmpty()) {
                continue;
            }
            matches.add(new StateMatch(extractor, objectId, Map.copyOf(stateValues)));
        }
        return matches.isEmpty() ? List.of() : List.copyOf(matches);
    }

    private Object resolveAttribute(Map<String, Object> attributes, String path) {
        if (attributes == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = attributes;
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            Object next = map.get(segment);
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return Objects.toString(value);
    }

    static final class StateMatch {
        private final StateExtractorDefinition extractor;
        private final String objectId;
        private final Map<String, String> stateValues;

        StateMatch(StateExtractorDefinition extractor, String objectId, Map<String, String> stateValues) {
            this.extractor = extractor;
            this.objectId = objectId;
            this.stateValues = stateValues;
        }

        StateExtractorDefinition extractor() {
            return extractor;
        }

        String objectId() {
            return objectId;
        }

        Map<String, String> stateValues() {
            return stateValues;
        }
    }
}

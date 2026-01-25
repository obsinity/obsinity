package com.obsinity.service.core.state.transition.outcome;

import com.obsinity.service.core.state.transition.counter.TerminalStateResolver;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransitionOutcomeService {
    private static final String OBSINITY_SERVICE_ID = "obsinity";

    private final TransitionOutcomeRepository repository;
    private final TerminalStateResolver terminalStateResolver;

    public void recordFirstSeen(
            UUID serviceId, String objectType, String objectId, String attribute, String state, Instant timestamp) {
        repository.recordFirstSeen(serviceId, objectType, objectId, attribute, state, timestamp);
    }

    public void recordObservedTerminalIfTerminal(
            UUID serviceId,
            String eventServiceId,
            String objectType,
            String objectId,
            String attribute,
            String state,
            Instant timestamp,
            String eventId) {
        if (eventServiceId == null || OBSINITY_SERVICE_ID.equals(eventServiceId)) {
            return;
        }
        if (!terminalStateResolver.terminalStates(serviceId, objectType).contains(state)) {
            return;
        }
        repository.recordObservedOutcome(serviceId, objectType, objectId, attribute, state, timestamp, eventId);
    }

    public void recordObservedTerminal(
            UUID serviceId,
            String objectType,
            String objectId,
            String attribute,
            String state,
            Instant timestamp,
            String eventId) {
        repository.recordObservedOutcome(serviceId, objectType, objectId, attribute, state, timestamp, eventId);
    }

    public void recordSyntheticTerminal(
            UUID serviceId,
            String objectType,
            String objectId,
            String attribute,
            String state,
            Instant timestamp,
            String syntheticEventId) {
        repository.recordSyntheticOutcome(
                serviceId, objectType, objectId, attribute, state, timestamp, syntheticEventId);
    }
}

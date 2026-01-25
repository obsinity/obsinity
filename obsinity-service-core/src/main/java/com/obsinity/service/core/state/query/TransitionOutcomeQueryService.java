package com.obsinity.service.core.state.query;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransitionOutcomeQueryService {
    private final TransitionOutcomeQueryRepository repository;

    public TransitionOutcomeQueryService(TransitionOutcomeQueryRepository repository) {
        this.repository = repository;
    }

    public LifecycleView fetchLifecycle(UUID serviceId, String objectType, String objectId, String attribute) {
        List<TransitionOutcomeQueryRepository.FirstSeenRow> states =
                repository.fetchFirstSeenStates(serviceId, objectType, objectId, attribute);
        TransitionOutcomeQueryRepository.OutcomeRow outcome =
                repository.fetchOutcome(serviceId, objectType, objectId, attribute);
        return new LifecycleView(states, outcome);
    }

    public record LifecycleView(
            List<TransitionOutcomeQueryRepository.FirstSeenRow> firstSeenStates,
            TransitionOutcomeQueryRepository.OutcomeRow outcome) {}
}

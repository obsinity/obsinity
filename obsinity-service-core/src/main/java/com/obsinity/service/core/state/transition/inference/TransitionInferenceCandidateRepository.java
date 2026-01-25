package com.obsinity.service.core.state.transition.inference;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransitionInferenceCandidateRepository {
    List<TransitionInferenceCandidate> findEligible(UUID serviceId, String objectType, Instant cutoff, int limit);
}

package com.obsinity.reference.demodata;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface ProfileGeneratorRepository {

    long countProfiles();

    int insertProfiles(List<UUID> ids, String initialState, Instant now);

    List<ProfileCandidate> selectCandidates(
            String fromState, Instant upperBound, Instant lowerBoundInclusive, int limit);

    int casUpdateState(UUID id, String fromState, Instant expectedStateChangedAt, String toState, Instant now);
}

package com.obsinity.service.core.model.config;

import java.util.List;

/** Declarative configuration for a state extractor. */
public record StateExtractorConfig(
        String rawType,
        String objectType,
        String objectIdField,
        List<String> stateAttributes,
        TransitionPolicyConfig transitionPolicy) {

    public record TransitionPolicyConfig(List<String> fromStates) {}
}

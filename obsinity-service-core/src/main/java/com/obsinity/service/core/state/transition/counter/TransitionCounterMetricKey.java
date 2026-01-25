package com.obsinity.service.core.state.transition.counter;

import java.util.UUID;

public record TransitionCounterMetricKey(
        UUID serviceId, String objectType, String attribute, String counterName, String fromState, String toState) {
    public static final String INITIAL_STATE = "(init)";

    String storageToState() {
        return toState != null ? toState : TransitionCounterPostingService.OPEN_STATE;
    }

    String storageFromState() {
        return storageFromState(fromState);
    }

    public static String storageFromState(String fromState) {
        return fromState != null ? fromState : INITIAL_STATE;
    }

    String dedupKey() {
        return serviceId
                + "|"
                + objectType
                + "|"
                + attribute
                + "|"
                + counterName
                + "|"
                + storageFromState()
                + "|"
                + (toState != null ? toState : "<open>");
    }
}

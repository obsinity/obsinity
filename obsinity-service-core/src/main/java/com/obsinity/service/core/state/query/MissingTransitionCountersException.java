package com.obsinity.service.core.state.query;

import java.util.List;

public class MissingTransitionCountersException extends IllegalArgumentException {
    private final List<MissingPair> missingPairs;

    public MissingTransitionCountersException(List<MissingPair> missingPairs) {
        super("Missing transition counters for requested from/to pairs");
        this.missingPairs = missingPairs == null ? List.of() : List.copyOf(missingPairs);
    }

    public List<MissingPair> missingPairs() {
        return missingPairs;
    }

    public record MissingPair(String fromState, String toState) {}
}

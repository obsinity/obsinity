package com.obsinity.service.core.state.query;

import java.util.List;

public record TransitionRatioQueryResult(long totalCount, List<TransitionRatioEntry> transitions) {

    public record TransitionRatioEntry(String fromState, String toState, long count, double ratio) {}
}

package com.obsinity.service.core.state.query;

import java.util.List;

public record StateTransitionQueryWindow(String start, String end, List<Entry> transitions) {
    public record Entry(String fromState, String toState, long count) {}
}

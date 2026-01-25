package com.obsinity.service.core.state.transition.counter;

import java.util.List;

public record TransitionCounterFootprintEntry(String counterName, List<String> fromStates, String toState) {}

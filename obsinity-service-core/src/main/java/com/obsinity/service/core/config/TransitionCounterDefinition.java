package com.obsinity.service.core.config;

import java.util.List;

/** Materialized transition counter definition derived from service config. */
public record TransitionCounterDefinition(
        String name,
        String objectType,
        String toState,
        FromMode fromMode,
        List<String> fromStates,
        boolean untilTerminal) {}

package com.obsinity.service.core.model.config;

/**
 * Declarative configuration for state transition counters.
 *
 * <p>from may be omitted (default last), set to "*" (any seen), or a list of states.
 */
public record TransitionCounterConfig(String name, String objectType, String to, Object from, Boolean untilTerminal) {}

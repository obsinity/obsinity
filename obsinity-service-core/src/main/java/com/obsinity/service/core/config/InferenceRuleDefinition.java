package com.obsinity.service.core.config;

import java.time.Duration;

public record InferenceRuleDefinition(
        String id,
        String objectType,
        boolean nonTerminalOnly,
        Duration idleFor,
        String emitState,
        String emitServiceId,
        String reason) {}

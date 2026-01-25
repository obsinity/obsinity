package com.obsinity.service.core.model.config;

public record InferenceRuleConfig(String id, String objectType, When when, Emit emit) {
    public record When(Boolean nonTerminalOnly, String idleFor) {}

    public record Emit(String state, String serviceId, String reason) {}
}

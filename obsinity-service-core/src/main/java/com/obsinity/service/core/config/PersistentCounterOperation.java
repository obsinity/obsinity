package com.obsinity.service.core.config;

import java.util.Locale;

public enum PersistentCounterOperation {
    INCREMENT,
    DECREMENT;

    public static PersistentCounterOperation fromValue(String value) {
        if (value == null || value.isBlank()) {
            return INCREMENT;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DECREMENT", "DEC", "DECR", "-" -> DECREMENT;
            default -> INCREMENT;
        };
    }
}

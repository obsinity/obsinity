package com.obsinity.service.core.counter;

import java.time.Duration;

/** Utility to parse simplified duration strings like "5s" as well as ISO-8601 "PT5S". */
public final class DurationParser {

    private DurationParser() {}

    public static Duration parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Duration cannot be null or empty");
        }

        String trimmed = input.trim();
        try {
            return Duration.parse(trimmed);
        } catch (Exception ignored) {
            // fall through to shorthand parsing
        }

        String lower = trimmed.toLowerCase();
        long value;
        if (lower.endsWith("ms")) {
            value = Long.parseLong(lower.substring(0, lower.length() - 2));
            return Duration.ofMillis(value);
        }
        if (lower.endsWith("s")) {
            value = Long.parseLong(lower.substring(0, lower.length() - 1));
            return Duration.ofSeconds(value);
        }
        if (lower.endsWith("m")) {
            value = Long.parseLong(lower.substring(0, lower.length() - 1));
            return Duration.ofMinutes(value);
        }
        if (lower.endsWith("h")) {
            value = Long.parseLong(lower.substring(0, lower.length() - 1));
            return Duration.ofHours(value);
        }
        if (lower.endsWith("d")) {
            value = Long.parseLong(lower.substring(0, lower.length() - 1));
            return Duration.ofDays(value);
        }

        throw new IllegalArgumentException("Unsupported duration format: " + input);
    }
}

package com.obsinity.service.core.config;

import java.util.List;
import java.util.Locale;

public record RatioQueryDefinition(
        String name,
        Source source,
        String objectType,
        String attribute,
        Window window,
        List<Item> items,
        Output output,
        Behavior behavior) {

    public enum Source {
        STATES,
        TRANSITIONS,
        MIXED;

        public static Source fromConfigValue(String value) {
            if (value == null || value.isBlank()) {
                return STATES;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "states" -> STATES;
                case "transitions" -> TRANSITIONS;
                case "mixed" -> MIXED;
                default -> throw new IllegalArgumentException("Unsupported ratio source: " + value);
            };
        }
    }

    public enum OutputFormat {
        GRAFANA_PIE,
        TABLE,
        SERIES;

        public static OutputFormat fromConfigValue(String value) {
            if (value == null || value.isBlank()) {
                return GRAFANA_PIE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "grafana_pie" -> GRAFANA_PIE;
                case "table" -> TABLE;
                case "series" -> SERIES;
                default -> throw new IllegalArgumentException("Unsupported ratio output format: " + value);
            };
        }
    }

    public enum ValueMode {
        COUNT,
        PERCENT,
        RATIO;

        public static ValueMode fromConfigValue(String value) {
            if (value == null || value.isBlank()) {
                return COUNT;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "count" -> COUNT;
                case "percent" -> PERCENT;
                case "ratio" -> RATIO;
                default -> throw new IllegalArgumentException("Unsupported ratio value mode: " + value);
            };
        }
    }

    public enum ZeroTotalBehavior {
        ZEROS,
        EMPTY,
        ERROR;

        public static ZeroTotalBehavior fromConfigValue(String value) {
            if (value == null || value.isBlank()) {
                return ZEROS;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "zeros" -> ZEROS;
                case "empty" -> EMPTY;
                case "error" -> ERROR;
                default -> throw new IllegalArgumentException("Unsupported zeroTotal behavior: " + value);
            };
        }
    }

    public enum MissingItemBehavior {
        ZERO,
        ERROR;

        public static MissingItemBehavior fromConfigValue(String value) {
            if (value == null || value.isBlank()) {
                return ZERO;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "zero" -> ZERO;
                case "error" -> ERROR;
                default -> throw new IllegalArgumentException("Unsupported missingItem behavior: " + value);
            };
        }
    }

    public record Window(String from, String to) {}

    public record Item(String state, String transition, String label) {}

    public record Output(
            OutputFormat format,
            ValueMode valueMode,
            boolean includeRaw,
            boolean includePercent,
            boolean includeRatio,
            int decimals) {}

    public record Behavior(ZeroTotalBehavior zeroTotal, MissingItemBehavior missingItem) {}
}

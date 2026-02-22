package com.obsinity.service.core.model.config;

import java.util.List;

public record RatioQueryConfig(
        String name,
        String type,
        String source,
        String objectType,
        String attribute,
        Window window,
        List<Item> items,
        Output output,
        Behavior behavior) {

    public record Window(String from, String to) {}

    public record Item(String state, String transition, String label) {}

    public record Output(
            String format,
            String value,
            Boolean includeRaw,
            Boolean includePercent,
            Boolean includeRatio,
            Integer decimals) {}

    public record Behavior(String zeroTotal, String missingItem) {}
}

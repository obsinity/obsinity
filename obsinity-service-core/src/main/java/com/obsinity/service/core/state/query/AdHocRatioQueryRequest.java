package com.obsinity.service.core.state.query;

import java.util.List;

public record AdHocRatioQueryRequest(
        String serviceKey,
        String name,
        String source,
        String objectType,
        String attribute,
        List<Item> items,
        String from,
        String to,
        String value,
        Boolean includeRaw,
        Boolean includePercent,
        Boolean includeRatio,
        Integer decimals,
        Boolean latestMinute,
        String zeroTotal,
        String missingItem) {

    public record Item(String state, String transition, String label) {}
}

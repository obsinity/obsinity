package com.obsinity.service.core.counter;

import com.obsinity.service.core.api.ResponseFormat;
import java.util.List;
import java.util.Map;

/** Request payload for counter query execution. */
public record CounterQueryRequest(
        String serviceKey,
        String eventType,
        String counterName,
        Map<String, List<String>> key,
        String interval,
        String start,
        String end,
        Limits limits,
        ResponseFormat format) {

    public record Limits(Integer offset, Integer limit) {}
}

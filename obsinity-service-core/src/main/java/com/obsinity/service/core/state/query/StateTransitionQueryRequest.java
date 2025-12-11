package com.obsinity.service.core.state.query;

import com.obsinity.service.core.api.ResponseFormat;
import java.util.List;

public record StateTransitionQueryRequest(
        String serviceKey,
        String objectType,
        String attribute,
        List<String> fromStates,
        List<String> toStates,
        String interval,
        String start,
        String end,
        Limits limits,
        ResponseFormat format) {

    public record Limits(Integer offset, Integer limit) {}
}

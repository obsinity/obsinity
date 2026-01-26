package com.obsinity.service.core.state.query;

import com.obsinity.service.core.api.ResponseFormat;
import java.util.List;

public record TransitionRatioQueryRequest(
        String serviceKey,
        String objectType,
        String attribute,
        List<TransitionSpec> transitions,
        String interval,
        String start,
        String end,
        Boolean groupByFromState,
        ResponseFormat format) {

    public record TransitionSpec(List<String> from, List<String> to) {}
}

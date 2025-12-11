package com.obsinity.service.core.state.query;

import com.obsinity.service.core.api.ResponseFormat;
import java.util.List;

public record StateCountTimeseriesQueryRequest(
        String serviceKey,
        String objectType,
        String attribute,
        List<String> states,
        String interval,
        String start,
        String end,
        Limits limits,
        ResponseFormat format) {

    public record Limits(Integer offset, Integer limit) {}
}

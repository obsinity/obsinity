package com.obsinity.service.core.state.query;

import com.obsinity.service.core.api.ResponseFormat;
import java.util.List;

public record StateCountQueryRequest(
        String serviceKey,
        String objectType,
        String attribute,
        List<String> states,
        Limits limits,
        ResponseFormat format) {

    public record Limits(Integer offset, Integer limit) {}
}

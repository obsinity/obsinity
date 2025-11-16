package com.obsinity.service.core.state.query;

import java.util.List;

public record StateCountQueryRequest(
        String serviceKey,
        String objectType,
        String attribute,
        List<String> states,
        Limits limits) {

    public record Limits(Integer offset, Integer limit) {}
}

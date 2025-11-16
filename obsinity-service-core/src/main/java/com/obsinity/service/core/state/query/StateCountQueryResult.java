package com.obsinity.service.core.state.query;

import java.util.List;

public record StateCountQueryResult(List<StateCountEntry> states, int offset, int limit, long total) {

    public record StateCountEntry(String state, long count) {}
}

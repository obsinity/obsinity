package com.obsinity.controller.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obsinity.service.core.counter.CounterQueryRequest;
import com.obsinity.service.core.counter.CounterQueryResult;
import com.obsinity.service.core.counter.CounterQueryWindow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CounterQueryHalResponse(
        int count, int total, int limit, int offset, Data data, Map<String, HalLink> links) {

    record Data(List<CounterQueryWindow> intervals) {}

    record HalLink(String href, String method, Object body) {}

    static CounterQueryHalResponse from(String href, CounterQueryRequest request, CounterQueryResult result) {
        int count = result.windows().size();
        int total = result.totalWindows();
        int offset = result.offset();
        int limit = determineLimit(request, result, count, total);

        Map<String, HalLink> links = buildLinks(href, request, offset, limit, count, total);
        return new CounterQueryHalResponse(count, total, limit, offset, new Data(result.windows()), links);
    }

    private static int determineLimit(CounterQueryRequest request, CounterQueryResult result, int count, int total) {
        Integer requested = request.limits() != null ? request.limits().limit() : null;
        if (requested != null && requested > 0 && requested < Integer.MAX_VALUE) {
            return requested;
        }
        if (count > 0) {
            return count;
        }
        return total;
    }

    private static Map<String, HalLink> buildLinks(
            String href, CounterQueryRequest request, int offset, int limit, int count, int total) {
        Map<String, HalLink> links = new LinkedHashMap<>();
        links.put("self", new HalLink(href, "POST", withLimits(request, offset, limit)));

        if (limit > 0) {
            links.put("first", new HalLink(href, "POST", withLimits(request, 0, limit)));
        }

        if (limit > 0 && offset > 0) {
            int previousOffset = Math.max(0, offset - limit);
            links.put("prev", new HalLink(href, "POST", withLimits(request, previousOffset, limit)));
        }

        if (limit > 0 && count > 0 && offset + count < total) {
            int nextOffset = offset + limit;
            links.put("next", new HalLink(href, "POST", withLimits(request, nextOffset, limit)));
        }

        if (limit > 0 && total > 0) {
            int lastOffset = ((total - 1) / limit) * limit;
            links.put("last", new HalLink(href, "POST", withLimits(request, lastOffset, limit)));
        }

        return links;
    }

    private static CounterQueryRequest withLimits(CounterQueryRequest base, int offset, int limit) {
        CounterQueryRequest.Limits limits = new CounterQueryRequest.Limits(offset, limit);
        return new CounterQueryRequest(
                base.serviceKey(),
                base.eventType(),
                base.counterName(),
                base.key(),
                base.interval(),
                base.start(),
                base.end(),
                limits);
    }
}

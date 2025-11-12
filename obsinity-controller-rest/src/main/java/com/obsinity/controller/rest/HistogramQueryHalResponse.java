package com.obsinity.controller.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obsinity.service.core.histogram.HistogramQueryRequest;
import com.obsinity.service.core.histogram.HistogramQueryResult;
import com.obsinity.service.core.histogram.HistogramQueryWindow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HistogramQueryHalResponse(
        int count,
        int total,
        int limit,
        int offset,
        List<Double> defaultPercentiles,
        Data data,
        Map<String, HalLink> links) {

    record Data(List<HistogramQueryWindow> intervals) {}

    record HalLink(String href, String method, Object body) {}

    static HistogramQueryHalResponse from(String href, HistogramQueryRequest request, HistogramQueryResult result) {
        int count = result.windows().size();
        int total = result.totalWindows();
        int offset = result.offset();
        int limit = determineLimit(request, count, total);

        Map<String, HalLink> links = buildLinks(href, request, offset, limit, count, total);
        return new HistogramQueryHalResponse(
                count, total, limit, offset, result.defaultPercentiles(), new Data(result.windows()), links);
    }

    private static int determineLimit(HistogramQueryRequest request, int count, int total) {
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
            String href, HistogramQueryRequest request, int offset, int limit, int count, int total) {
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

    private static HistogramQueryRequest withLimits(HistogramQueryRequest base, int offset, int limit) {
        HistogramQueryRequest.Limits limits = new HistogramQueryRequest.Limits(offset, limit);
        return new HistogramQueryRequest(
                base.serviceKey(),
                base.eventType(),
                base.histogramName(),
                base.key(),
                base.interval(),
                base.start(),
                base.end(),
                base.percentiles(),
                limits);
    }
}

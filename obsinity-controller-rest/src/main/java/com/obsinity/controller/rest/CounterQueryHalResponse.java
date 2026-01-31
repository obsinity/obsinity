package com.obsinity.controller.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.api.FrictionlessData;
import com.obsinity.service.core.api.ResponseFormat;
import com.obsinity.service.core.counter.CounterQueryRequest;
import com.obsinity.service.core.counter.CounterQueryResult;
import com.obsinity.service.core.counter.CounterQueryWindow;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CounterQueryHalResponse(
        int count,
        int total,
        int limit,
        int offset,
        Object data,
        List<Map<String, Object>> rows,
        Map<String, HalLink> links,
        String format) {

    record Data(List<CounterQueryWindow> intervals) {}

    record HalLink(String href, String method, Object body) {}

    static CounterQueryHalResponse from(
            String href,
            CounterQueryRequest request,
            CounterQueryResult result,
            ResponseFormat responseFormat,
            ObjectMapper mapper) {
        int count = result.windows().size();
        int total = result.totalWindows();
        int offset = result.offset();
        int limit = determineLimit(request, result, count, total);
        ResponseFormat format = ResponseFormat.defaulted(responseFormat);

        String effectiveStart = resolveBoundary(request.start(), result.start());
        String effectiveEnd = resolveBoundary(request.end(), result.end());
        Map<String, HalLink> links =
                buildLinks(href, request, offset, limit, count, total, effectiveStart, effectiveEnd);
        List<Map<String, Object>> rows = flattenWindows(result);
        Object data = format == ResponseFormat.COLUMNAR
                ? FrictionlessData.columnar(rows, mapper)
                : new Data(result.windows());
        return new CounterQueryHalResponse(count, total, limit, offset, data, rows, links, format.wireValue());
    }

    private static List<Map<String, Object>> flattenWindows(CounterQueryResult result) {
        return result.windows().stream()
                .flatMap(w -> w.counts().stream().filter(c -> c.count() > 0).map(c -> toRow(w, c)))
                .toList();
    }

    private static Map<String, Object> toRow(CounterQueryWindow window, CounterQueryWindow.CountEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("from", window.from());
        row.put("to", window.to());
        if (entry.key() != null) {
            row.putAll(entry.key());
        }
        row.put("count", entry.count());
        return row;
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
            String href,
            CounterQueryRequest request,
            int offset,
            int limit,
            int count,
            int total,
            String start,
            String end) {
        Map<String, HalLink> links = new LinkedHashMap<>();
        links.put("self", new HalLink(href, "POST", withLimits(request, offset, limit, start, end)));

        if (limit > 0) {
            links.put("first", new HalLink(href, "POST", withLimits(request, 0, limit, start, end)));
        }

        if (limit > 0 && offset > 0) {
            int previousOffset = Math.max(0, offset - limit);
            links.put("prev", new HalLink(href, "POST", withLimits(request, previousOffset, limit, start, end)));
        }

        if (limit > 0 && count > 0 && offset + count < total) {
            int nextOffset = offset + limit;
            links.put("next", new HalLink(href, "POST", withLimits(request, nextOffset, limit, start, end)));
        }

        if (limit > 0 && total > 0) {
            int lastOffset = ((total - 1) / limit) * limit;
            links.put("last", new HalLink(href, "POST", withLimits(request, lastOffset, limit, start, end)));
        }

        return links;
    }

    private static CounterQueryRequest withLimits(
            CounterQueryRequest base, int offset, int limit, String start, String end) {
        CounterQueryRequest.Limits limits = new CounterQueryRequest.Limits(offset, limit);
        return new CounterQueryRequest(
                base.serviceKey(),
                base.eventType(),
                base.counterName(),
                base.key(),
                base.interval(),
                start,
                end,
                limits,
                base.format());
    }

    private static String resolveBoundary(String requested, java.time.Instant calculated) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (calculated == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(calculated);
    }
}

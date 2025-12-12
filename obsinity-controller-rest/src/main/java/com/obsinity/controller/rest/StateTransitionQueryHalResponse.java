package com.obsinity.controller.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.api.FrictionlessData;
import com.obsinity.service.core.api.ResponseFormat;
import com.obsinity.service.core.state.query.StateTransitionQueryRequest;
import com.obsinity.service.core.state.query.StateTransitionQueryResult;
import com.obsinity.service.core.state.query.StateTransitionQueryWindow;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StateTransitionQueryHalResponse(
        int count, int total, int limit, int offset, Object data, Map<String, HalLink> links, String format) {

    record Data(List<StateTransitionQueryWindow> intervals) {}

    record HalLink(String href, String method, Object body) {}

    static StateTransitionQueryHalResponse from(
            String href,
            StateTransitionQueryRequest request,
            StateTransitionQueryResult result,
            ResponseFormat responseFormat,
            ObjectMapper mapper) {
        int count = result.windows().size();
        int total = result.totalIntervals();
        int offset = result.offset();
        int limit = determineLimit(request, count, total);
        ResponseFormat format = ResponseFormat.defaulted(responseFormat);

        String effectiveStart = resolveBoundary(request.start(), result.start());
        String effectiveEnd = resolveBoundary(request.end(), result.end());
        Map<String, HalLink> links =
                buildLinks(href, request, offset, limit, count, total, effectiveStart, effectiveEnd);

        Object data = format == ResponseFormat.COLUMNAR
                ? FrictionlessData.columnar(flattenWindows(result), mapper)
                : new Data(result.windows());

        return new StateTransitionQueryHalResponse(count, total, limit, offset, data, links, format.wireValue());
    }

    private static List<Map<String, Object>> flattenWindows(StateTransitionQueryResult result) {
        return result.windows().stream()
                .flatMap(w -> w.transitions().stream()
                        .filter(e -> e.count() > 0)
                        .map(e -> toRow(w, e)))
                .toList();
    }

    private static Map<String, Object> toRow(StateTransitionQueryWindow window, StateTransitionQueryWindow.Entry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("from", window.start());
        row.put("to", window.end());
        row.put("fromState", entry.fromState());
        row.put("toState", entry.toState());
        row.put("count", entry.count());
        return row;
    }

    private static Map<String, HalLink> buildLinks(
            String href,
            StateTransitionQueryRequest request,
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
            int prevOffset = Math.max(0, offset - limit);
            links.put("prev", new HalLink(href, "POST", withLimits(request, prevOffset, limit, start, end)));
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

    private static StateTransitionQueryRequest withLimits(
            StateTransitionQueryRequest base, int offset, int limit, String start, String end) {
        StateTransitionQueryRequest.Limits limits = new StateTransitionQueryRequest.Limits(offset, limit);
        return new StateTransitionQueryRequest(
                base.serviceKey(),
                base.objectType(),
                base.attribute(),
                base.fromStates(),
                base.toStates(),
                base.interval(),
                start,
                end,
                limits,
                base.format());
    }

    private static int determineLimit(StateTransitionQueryRequest request, int count, int total) {
        Integer requested = request.limits() != null ? request.limits().limit() : null;
        if (requested != null && requested > 0 && requested < Integer.MAX_VALUE) {
            return requested;
        }
        if (count > 0) {
            return count;
        }
        return total;
    }

    private static String resolveBoundary(String requested, Instant calculated) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (calculated == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(calculated);
    }
}

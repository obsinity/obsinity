package com.obsinity.controller.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.api.FrictionlessData;
import com.obsinity.service.core.api.ResponseFormat;
import com.obsinity.service.core.histogram.HistogramQueryRequest;
import com.obsinity.service.core.histogram.HistogramQueryResult;
import com.obsinity.service.core.histogram.HistogramQueryWindow;
import java.time.format.DateTimeFormatter;
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
        Object data,
        Map<String, HalLink> links,
        String format) {

    record Data(List<HistogramQueryWindow> intervals) {}

    record HalLink(String href, String method, Object body) {}

    static HistogramQueryHalResponse from(
            String href,
            HistogramQueryRequest request,
            HistogramQueryResult result,
            ResponseFormat responseFormat,
            ObjectMapper mapper) {
        int count = result.windows().size();
        int total = result.totalWindows();
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
        return new HistogramQueryHalResponse(
                count, total, limit, offset, result.defaultPercentiles(), data, links, format.wireValue());
    }

    private static List<Map<String, Object>> flattenWindows(HistogramQueryResult result) {
        List<Double> percentiles = result.defaultPercentiles();
        return result.windows().stream()
                .flatMap(w -> w.series().stream()
                        .filter(series -> series.samples() > 0)
                        .map(series -> toRow(w, series, percentiles)))
                .toList();
    }

    private static Map<String, Object> toRow(
            HistogramQueryWindow window, HistogramQueryWindow.Series series, List<Double> percentiles) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("from", window.from());
        row.put("to", window.to());
        if (series.key() != null) {
            row.putAll(series.key());
        }
        row.put("samples", series.samples());
        row.put("sum", series.sum());
        row.put("mean", series.mean());
        if (percentiles != null && series.percentiles() != null) {
            for (Double p : percentiles) {
                if (p == null) continue;
                Double val = series.percentiles().get(p);
                if (val == null) {
                    // Percentiles map keys are strings; fall back to string lookup
                    val = series.percentiles().get(Double.valueOf(p.toString()));
                }
                row.put("p" + percentileLabel(p), val);
            }
        }
        return row;
    }

    private static String percentileLabel(Double p) {
        if (p == null) return "";
        long scaled = Math.round(p * 100);
        return String.valueOf(scaled);
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
            String href,
            HistogramQueryRequest request,
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

    private static HistogramQueryRequest withLimits(
            HistogramQueryRequest base, int offset, int limit, String start, String end) {
        HistogramQueryRequest.Limits limits = new HistogramQueryRequest.Limits(offset, limit);
        return new HistogramQueryRequest(
                base.serviceKey(),
                base.eventType(),
                base.histogramName(),
                base.key(),
                base.interval(),
                start,
                end,
                base.percentiles(),
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

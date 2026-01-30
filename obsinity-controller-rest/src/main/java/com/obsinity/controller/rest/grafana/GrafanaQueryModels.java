package com.obsinity.controller.rest.grafana;

import java.util.List;
import java.util.Map;

public final class GrafanaQueryModels {

    private GrafanaQueryModels() {}

    public record GrafanaQueryRequest(
            Range range,
            String timezone,
            Long intervalMs,
            Integer maxDataPoints,
            List<GrafanaSubQuery> queries) {}

    public record Range(String from, String to, Long fromMs, Long toMs) {}

    public record GrafanaSubQuery(
            String refId,
            String kind,
            String format,
            String bucket,
            String serviceKey,
            String eventType,
            String histogramName,
            Map<String, List<String>> filters,
            List<Double> percentiles,
            List<String> groupBy,
            String objectType,
            String attribute,
            List<String> states) {}

    public record GrafanaQueryResponse(List<GrafanaResult> results) {}

    public record GrafanaResult(String refId, List<DataFrame> frames) {}

    public record DataFrame(String name, List<Field> fields, List<List<Object>> values) {}

    public record Field(String name, String type, Map<String, String> labels) {}
}

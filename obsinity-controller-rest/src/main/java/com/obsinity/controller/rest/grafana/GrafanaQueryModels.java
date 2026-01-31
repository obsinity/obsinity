package com.obsinity.controller.rest.grafana;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import java.util.Map;

public final class GrafanaQueryModels {

    private GrafanaQueryModels() {}

    public record GrafanaQueryRequest(
            Range range,
            String timezone,
            @JsonDeserialize(using = FlexibleLongDeserializer.class) Long intervalMs,
            @JsonDeserialize(using = FlexibleIntegerDeserializer.class) Integer maxDataPoints,
            List<GrafanaSubQuery> queries) {}

    public record Range(
            String from,
            String to,
            @JsonDeserialize(using = FlexibleLongDeserializer.class) Long fromMs,
            @JsonDeserialize(using = FlexibleLongDeserializer.class) Long toMs) {}

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

    public record GrafanaQueryResponse(Map<String, GrafanaResult> results, List<Map<String, Object>> rows) {}

    public record GrafanaResult(List<Frame> frames, List<Map<String, Object>> rows) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Frame(Schema schema, FrameData data, List<Field> fields, List<List<Object>> values) {}

    public record Schema(String refId, String name, List<Field> fields) {}

    public record Field(
            String name,
            String type,
            Map<String, Object> config,
            Map<String, String> labels,
            Map<String, String> typeInfo) {}

    public record FrameData(List<List<Object>> values) {}
}

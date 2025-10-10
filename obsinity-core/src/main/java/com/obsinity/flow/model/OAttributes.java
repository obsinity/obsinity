package com.obsinity.flow.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;

@JsonInclude(Include.NON_NULL)
@Getter
@Setter
public final class OAttributes {
    private final Map<String, Object> attributes;

    public OAttributes() {
        this.attributes = new LinkedHashMap<>();
    }

    @JsonCreator
    public OAttributes(Map<String, Object> attributes) {
        if (attributes != null) {
            this.attributes = new LinkedHashMap<>(attributes);
        } else {
            this.attributes = new LinkedHashMap<>();
        }
    }

    public static OAttributes empty() {
        return new OAttributes(new LinkedHashMap<>());
    }

    /** expose as plain JSON object: {"k":"v"} */
    @JsonAnyGetter
    public Map<String, Object> map() {
        return attributes;
    }

    /** accept arbitrary keys on input */
    @JsonAnySetter
    public void put(String key, Object value) {
        if (key != null) attributes.put(key, value);
    }

    public Attributes toOtel() {
        AttributesBuilder b = Attributes.builder();
        attributes.forEach((k, v) -> putBestEffort(b, k, v));
        return b.build();
    }

    public static OAttributes fromOtel(Attributes attrs) {
        if (attrs == null) return new OAttributes(new LinkedHashMap<>());
        Map<String, Object> m = attrs.asMap().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().getKey(), Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        return new OAttributes(m);
    }

    private static void putBestEffort(AttributesBuilder b, String k, Object v) {
        if (v == null) return;
        if (v instanceof String s) b.put(AttributeKey.stringKey(k), s);
        else if (v instanceof Boolean bo) b.put(AttributeKey.booleanKey(k), bo);
        else if (v instanceof Integer i) b.put(AttributeKey.longKey(k), i.longValue());
        else if (v instanceof Long l) b.put(AttributeKey.longKey(k), l);
        else if (v instanceof Float f) b.put(AttributeKey.doubleKey(k), f.doubleValue());
        else if (v instanceof Double d) b.put(AttributeKey.doubleKey(k), d);
        else if (v instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            @SuppressWarnings("unchecked")
            List<String> ss = (List<String>) list;
            b.put(AttributeKey.stringArrayKey(k), ss);
        } else {
            b.put(AttributeKey.stringKey(k), String.valueOf(v)); // last resort
        }
    }
}

package com.obsinity.telemetry.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.util.LinkedHashMap;
import java.util.Objects;

@JsonInclude(Include.NON_NULL)
public final class OLink {
    private final String traceId;
    private final String spanId;
    private final OAttributes attributes;

    public OLink(String traceId, String spanId, OAttributes attributes) {
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.spanId = Objects.requireNonNull(spanId, "spanId");
        this.attributes = (attributes == null ? new OAttributes(new LinkedHashMap<>()) : attributes);
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public OAttributes attributes() {
        return attributes;
    }

    /** Convert to OTEL API SpanContext (Attributes separate). */
    public SpanContext toSpanContext() {
        return SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
    }

    /** Convert to OTEL API Attributes (safe). */
    public Attributes toOtelAttributes() {
        return attributes.toOtel();
    }

    /** Construct from an API SpanContext + Attributes. */
    public static OLink fromSpanContext(SpanContext ctx, Attributes attrs) {
        if (ctx == null) return null;
        return new OLink(
                ctx.getTraceId(), ctx.getSpanId(), OAttributes.fromOtel(attrs == null ? Attributes.empty() : attrs));
    }
}

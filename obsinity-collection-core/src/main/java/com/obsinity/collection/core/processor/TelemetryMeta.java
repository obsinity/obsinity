package com.obsinity.collection.core.processor;

public final class TelemetryMeta {
    public final String domain;
    public final String kind;
    public final String statusCode;
    public final String statusMessage;
    public final String traceId;
    public final String spanId;
    public final String parentSpanId;
    public final String tracestate;

    private TelemetryMeta(Builder b) {
        this.domain = b.domain;
        this.kind = b.kind;
        this.statusCode = b.statusCode;
        this.statusMessage = b.statusMessage;
        this.traceId = b.traceId;
        this.spanId = b.spanId;
        this.parentSpanId = b.parentSpanId;
        this.tracestate = b.tracestate;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String domain, kind, statusCode, statusMessage, traceId, spanId, parentSpanId, tracestate;

        public Builder domain(String v) {
            this.domain = v;
            return this;
        }

        public Builder kind(String v) {
            this.kind = v;
            return this;
        }

        public Builder status(String code, String message) {
            this.statusCode = code;
            this.statusMessage = message;
            return this;
        }

        public Builder trace(String traceId, String spanId, String parentSpanId, String tracestate) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.tracestate = tracestate;
            return this;
        }

        public TelemetryMeta build() {
            return new TelemetryMeta(this);
        }
    }
}

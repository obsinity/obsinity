package com.obsinity.collection.core.processor;

public final class FlowMeta {
    public final String kind;
    public final String statusCode;
    public final String statusMessage;
    public final String traceId;
    public final String spanId;
    public final String parentSpanId;
    public final String tracestate;

    private FlowMeta(Builder b) {
        this.kind = b.kind;
        this.statusCode = b.statusCode;
        this.statusMessage = b.statusMessage;
        this.traceId = b.traceId;
        this.spanId = b.spanId;
        this.parentSpanId = b.parentSpanId;
        this.tracestate = b.tracestate;
    }

    public String kind() {
        return kind;
    }

    public String statusCode() {
        return statusCode;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public String tracestate() {
        return tracestate;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String kind, statusCode, statusMessage, traceId, spanId, parentSpanId, tracestate;

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

        public FlowMeta build() {
            return new FlowMeta(this);
        }
    }
}

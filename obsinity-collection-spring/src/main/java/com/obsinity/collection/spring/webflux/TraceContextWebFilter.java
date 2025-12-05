package com.obsinity.collection.spring.webflux;

import com.obsinity.flow.processor.FlowProcessorSupport;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public final class TraceContextWebFilter implements WebFilter {
    private final FlowProcessorSupport support;

    public TraceContextWebFilter(FlowProcessorSupport support) {
        this.support = support;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        String prevParentSpanId = MDC.get("parentSpanId");
        String prevTracestate = MDC.get("tracestate");
        String prevTraceparent = MDC.get("traceparent");

        HttpHeaders h = exchange.getRequest().getHeaders();
        String traceparent = h.getFirst("traceparent");
        String tracestate = h.getFirst("tracestate");
        String b3 = h.getFirst("b3");
        String traceId = null, spanId = null, parentSpanId = null;

        if (traceparent != null) {
            String[] tp = parseTraceparent(traceparent);
            if (tp != null) {
                traceId = tp[0];
                spanId = tp[1];
            }
        } else if (b3 != null) {
            String[] b3p = parseB3Single(b3);
            if (b3p != null) {
                traceId = b3p[0];
                spanId = b3p[1];
                parentSpanId = b3p[2];
            }
        } else {
            traceId = h.getFirst("X-B3-TraceId");
            spanId = h.getFirst("X-B3-SpanId");
            parentSpanId = h.getFirst("X-B3-ParentSpanId");
        }

        if (traceparent != null) MDC.put("traceparent", traceparent);
        if (tracestate != null) MDC.put("tracestate", tracestate);
        if (traceId != null) MDC.put("traceId", traceId);
        if (spanId != null) MDC.put("spanId", spanId);
        if (parentSpanId != null) MDC.put("parentSpanId", parentSpanId);

        return chain.filter(exchange).doFinally(sig -> {
            // Ensure ThreadLocal cleanup even if aspect fails (safety net for memory leak prevention)
            if (support != null) {
                support.cleanupThreadLocals();
            }
            restore("traceId", prevTraceId);
            restore("spanId", prevSpanId);
            restore("parentSpanId", prevParentSpanId);
            restore("tracestate", prevTracestate);
            restore("traceparent", prevTraceparent);
        });
    }

    private static void restore(String key, String prev) {
        if (prev == null) MDC.remove(key);
        else MDC.put(key, prev);
    }

    private static String[] parseTraceparent(String tp) {
        try {
            String s = tp.trim().toLowerCase(Locale.ROOT);
            String[] parts = s.split("-", -1);
            if (parts.length < 4) return null;
            String traceId = parts[1];
            String spanId = parts[2];
            if (traceId.length() == 32 && spanId.length() == 16) return new String[] {traceId, spanId, null};
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String[] parseB3Single(String b3) {
        try {
            String s = b3.trim();
            String[] parts = s.split("-", -1);
            if (parts.length >= 2) {
                String traceId = parts[0];
                String spanId = parts[1];
                String parentSpanId = (parts.length >= 4) ? parts[2] : null; // best-effort
                return new String[] {traceId, spanId, parentSpanId};
            }
        } catch (Exception ignore) {
        }
        return null;
    }
}

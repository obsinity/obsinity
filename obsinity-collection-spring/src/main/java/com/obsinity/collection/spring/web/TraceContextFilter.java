package com.obsinity.collection.spring.web;

import com.obsinity.collection.core.context.TelemetryContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.MDC;

public final class TraceContextFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        String prevParentSpanId = MDC.get("parentSpanId");
        String prevTracestate = MDC.get("tracestate");
        String prevTraceparent = MDC.get("traceparent");
        try {
            if (request instanceof HttpServletRequest req) {
                String traceparent = header(req, "traceparent");
                String tracestate = header(req, "tracestate");
                String b3 = header(req, "b3");
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
                    traceId = header(req, "X-B3-TraceId");
                    spanId = header(req, "X-B3-SpanId");
                    parentSpanId = header(req, "X-B3-ParentSpanId");
                }

                if (traceparent != null) MDC.put("traceparent", traceparent);
                if (tracestate != null) MDC.put("tracestate", tracestate);
                if (traceId != null) MDC.put("traceId", traceId);
                if (spanId != null) MDC.put("spanId", spanId);
                if (parentSpanId != null) MDC.put("parentSpanId", parentSpanId);

                if (traceId != null) TelemetryContext.putContext("traceId", traceId);
                if (spanId != null) TelemetryContext.putContext("spanId", spanId);
                if (parentSpanId != null) TelemetryContext.putContext("parentSpanId", parentSpanId);
                if (tracestate != null) TelemetryContext.putContext("tracestate", tracestate);
            }
            chain.doFilter(request, response);
        } finally {
            // Restore prior MDC state
            restore("traceId", prevTraceId);
            restore("spanId", prevSpanId);
            restore("parentSpanId", prevParentSpanId);
            restore("tracestate", prevTracestate);
            restore("traceparent", prevTraceparent);
            // Clear collection context to avoid thread reuse leaks
            TelemetryContext.clear();
        }
    }

    private static void restore(String key, String prev) {
        if (prev == null) MDC.remove(key);
        else MDC.put(key, prev);
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? null : v;
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

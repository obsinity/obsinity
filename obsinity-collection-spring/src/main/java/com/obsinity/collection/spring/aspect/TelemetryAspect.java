package com.obsinity.collection.spring.aspect;

import com.obsinity.collection.api.annotations.Domain;
import com.obsinity.collection.api.annotations.Flow;
import com.obsinity.collection.api.annotations.Kind;
import com.obsinity.collection.core.processor.TelemetryMeta;
import com.obsinity.collection.core.processor.TelemetryProcessor;
import com.obsinity.collection.spring.processor.AttributeParamExtractor;
import com.obsinity.collection.spring.processor.AttributeParamExtractor.AttrCtx;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

@Aspect
public class TelemetryAspect {
    private final TelemetryProcessor processor;

    public TelemetryAspect(TelemetryProcessor processor) {
        this.processor = processor;
    }

    @Around("@annotation(com.obsinity.collection.api.annotations.Flow) && execution(* *(..))")
    public Object aroundFlow(ProceedingJoinPoint pjp) throws Throwable {
        String name = resolveFlowName(pjp);
        AttrCtx ac = AttributeParamExtractor.extract(pjp);
        TelemetryMeta meta = buildMeta(pjp, null);
        processor.onFlowStarted(name, ac.attributes(), ac.context(), meta);
        try {
            Object result = pjp.proceed();
            TelemetryMeta ok = buildMeta(pjp, new StatusHint("OK", null));
            processor.onFlowCompleted(name, ac.attributes(), ac.context(), ok);
            return result;
        } catch (Throwable t) {
            var attrs = new java.util.LinkedHashMap<String, Object>(ac.attributes());
            attrs.put("error", t.getClass().getSimpleName());
            TelemetryMeta err = buildMeta(pjp, new StatusHint("ERROR", t.getMessage()));
            processor.onFlowFailed(name, t, attrs, ac.context(), err);
            throw t;
        }
    }

    private static String resolveFlowName(ProceedingJoinPoint pjp) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method m = ms.getMethod();
        Flow f = m.getAnnotation(Flow.class);
        if (f != null) {
            if (!f.name().isBlank()) return f.name();
            if (!f.value().isBlank()) return f.value();
        }
        return ms.toShortString();
    }

    private record StatusHint(String code, String message) {}

    private static TelemetryMeta buildMeta(ProceedingJoinPoint pjp, StatusHint status) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method m = ms.getMethod();
        TelemetryMeta.Builder b = TelemetryMeta.builder();
        Kind k = m.getAnnotation(Kind.class);
        if (k != null && !k.value().isBlank()) b.kind(k.value());
        Domain d = m.getAnnotation(Domain.class);
        if (d != null && !d.value().isBlank()) b.domain(d.value());
        if (status != null) b.status(status.code(), status.message());
        String traceId = null;
        String spanId = null;
        String parentSpanId = null;
        String tracestate = null;
        // Fallback to MDC if not provided in TelemetryContext
        if (traceId == null || spanId == null) {
            try {
                String tp = org.slf4j.MDC.get("traceparent");
                if (tp != null) {
                    var parsed = parseTraceparent(tp);
                    if (parsed != null) {
                        traceId = coalesce(traceId, parsed[0]);
                        spanId = coalesce(spanId, parsed[1]);
                    }
                }
                // B3 single header in MDC
                String b3 = org.slf4j.MDC.get("b3");
                if (b3 != null) {
                    var b3p = parseB3Single(b3);
                    if (b3p != null) {
                        traceId = coalesce(traceId, b3p[0]);
                        spanId = coalesce(spanId, b3p[1]);
                        parentSpanId = coalesce(parentSpanId, b3p[2]);
                    }
                }
                traceId = coalesce(traceId, org.slf4j.MDC.get("traceId"));
                spanId = coalesce(spanId, org.slf4j.MDC.get("spanId"));
                parentSpanId = coalesce(parentSpanId, org.slf4j.MDC.get("parentSpanId"));
                tracestate = coalesce(tracestate, org.slf4j.MDC.get("tracestate"));
                // B3 multi-keys
                traceId = coalesce(traceId, org.slf4j.MDC.get("X-B3-TraceId"));
                spanId = coalesce(spanId, org.slf4j.MDC.get("X-B3-SpanId"));
                parentSpanId = coalesce(parentSpanId, org.slf4j.MDC.get("X-B3-ParentSpanId"));
            } catch (Throwable ignore) {
                /* MDC not available or inaccessible */
            }
        }
        if (traceId != null || spanId != null || parentSpanId != null || tracestate != null)
            b.trace(traceId, spanId, parentSpanId, tracestate);
        return b.build();
    }

    private static String asString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String[] parseTraceparent(String tp) {
        try {
            String s = tp.trim().toLowerCase(java.util.Locale.ROOT);
            String[] parts = s.split("-", -1);
            if (parts.length < 4) return null;
            String traceId = parts[1];
            String spanId = parts[2];
            if (traceId.length() == 32 && spanId.length() == 16) return new String[] {traceId, spanId};
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b != null && !b.isBlank() ? b : null);
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

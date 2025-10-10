package com.obsinity.collection.spring.aspect;

import com.obsinity.collection.api.annotations.Flow;
import com.obsinity.collection.api.annotations.Kind;
import com.obsinity.collection.api.annotations.OrphanAlert;
import com.obsinity.collection.api.annotations.Step;
import com.obsinity.collection.core.processor.FlowMeta;
import com.obsinity.collection.core.processor.FlowProcessor;
import com.obsinity.collection.spring.processor.AttributeParamExtractor;
import com.obsinity.collection.spring.processor.AttributeParamExtractor.AttrCtx;
import com.obsinity.flow.model.FlowEvent;
import com.obsinity.flow.model.OAttributes;
import com.obsinity.flow.processor.FlowProcessorSupport;
import io.opentelemetry.api.trace.SpanKind;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

@Aspect
public class FlowAspect {
    private final FlowProcessor processor;
    private final FlowProcessorSupport support;

    public FlowAspect(FlowProcessor processor, FlowProcessorSupport support) {
        this.processor = processor;
        this.support = support;
    }

    @Around("@annotation(com.obsinity.collection.api.annotations.Flow) && execution(* *(..))")
    public Object aroundFlow(ProceedingJoinPoint pjp) throws Throwable {
        String name = resolveFlowName(pjp);
        AttrCtx ac = AttributeParamExtractor.extract(pjp);
        FlowMeta meta = buildMeta(pjp, null);
        processor.onFlowStarted(name, ac.attributes(), ac.context(), meta);
        try {
            Object result = pjp.proceed();
            FlowMeta ok = buildMeta(pjp, new StatusHint("OK", null));
            processor.onFlowCompleted(name, ac.attributes(), ac.context(), ok);
            return result;
        } catch (Throwable t) {
            var attrs = new java.util.LinkedHashMap<String, Object>(ac.attributes());
            attrs.put("error", t.toString());
            FlowMeta err = buildMeta(pjp, new StatusHint("ERROR", t.getMessage()));
            processor.onFlowFailed(name, t, attrs, ac.context(), err);
            throw t;
        }
    }

    @Around("@annotation(com.obsinity.collection.api.annotations.Step) && execution(* *(..))")
    public Object aroundStep(ProceedingJoinPoint pjp) throws Throwable {
        String name = resolveStepName(pjp);
        AttrCtx ac = AttributeParamExtractor.extract(pjp);

        FlowEvent holder = (support != null) ? support.currentHolder() : null;
        if (holder == null) {
            // Orphan step: log + auto-promote as a flow
            OrphanAlert oa = ((MethodSignature) pjp.getSignature()).getMethod().getAnnotation(OrphanAlert.class);
            if (support != null) support.logOrphanStep(name, oa != null ? oa.value() : OrphanAlert.Level.ERROR);
            try {
                processor.onFlowStarted(name, ac.attributes(), ac.context(), buildMeta(pjp, null));
                Object result = pjp.proceed();
                processor.onFlowCompleted(
                        name, ac.attributes(), ac.context(), buildMeta(pjp, new StatusHint("OK", null)));
                return result;
            } catch (Throwable t) {
                Map<String, Object> attrs = new LinkedHashMap<>(ac.attributes());
                attrs.putIfAbsent("error", t.getClass().getSimpleName());
                processor.onFlowFailed(
                        name, t, attrs, ac.context(), buildMeta(pjp, new StatusHint("ERROR", t.getMessage())));
                throw t;
            }
        }

        // In-flow step: mutate current holder and add nested event for duration
        holder.attributes().map().putAll(ac.attributes());
        holder.eventContext().putAll(ac.context());

        long startNano = System.nanoTime();
        long startEpochNanos = support != null ? support.unixNanos(Instant.now()) : 0L;
        OAttributes initial = new OAttributes(new LinkedHashMap<>(ac.attributes()));
        Kind stepKind = ((MethodSignature) pjp.getSignature()).getMethod().getAnnotation(Kind.class);
        String kindValue = (stepKind != null && stepKind.value() != null)
                ? stepKind.value().name()
                : SpanKind.INTERNAL.name();
        holder.beginStepEvent(name, startEpochNanos, startNano, initial, kindValue);
        try {
            Object result = pjp.proceed();
            holder.endStepEvent(support != null ? support.unixNanos(Instant.now()) : 0L, System.nanoTime(), null);
            return result;
        } catch (Throwable t) {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("error", t.getClass().getSimpleName());
            holder.endStepEvent(support != null ? support.unixNanos(Instant.now()) : 0L, System.nanoTime(), updates);
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

    private static String resolveStepName(ProceedingJoinPoint pjp) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method m = ms.getMethod();
        Step s = m.getAnnotation(Step.class);
        if (s != null) {
            if (!s.name().isBlank()) return s.name();
            if (!s.value().isBlank()) return s.value();
        }
        return ms.toShortString();
    }

    private record StatusHint(String code, String message) {}

    private static FlowMeta buildMeta(ProceedingJoinPoint pjp, StatusHint status) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method m = ms.getMethod();
        FlowMeta.Builder b = FlowMeta.builder();
        Kind k = m.getAnnotation(Kind.class);
        if (k != null && k.value() != null) b.kind(k.value().name());
        if (status != null) b.status(status.code(), status.message());
        String traceId = null;
        String spanId = null;
        String parentSpanId = null;
        String tracestate = null;
        // Fallback to MDC if not provided in FlowContext
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

package com.obsinity.collection.core.processor;

import com.obsinity.collection.core.dispatch.AsyncDispatchBus;
import com.obsinity.telemetry.model.FlowEvent;
import com.obsinity.telemetry.model.OStatus;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultTelemetryProcessor implements TelemetryProcessor {
    private final AsyncDispatchBus asyncBus;
    private final TelemetryProcessorSupport support;

    public DefaultTelemetryProcessor(AsyncDispatchBus asyncBus, TelemetryProcessorSupport support) {
        this.asyncBus = asyncBus;
        this.support = support;
    }

    @Override
    public void onFlowStarted(String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext) {
        var attrs = new LinkedHashMap<String, Object>(extraAttrs == null ? Map.of() : extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(extraContext == null ? Map.of() : extraContext);
        onFlowStarted(name, attrs, ctx, null);
    }

    @Override
    public void onFlowCompleted(String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext) {
        var attrs = new LinkedHashMap<String, Object>(extraAttrs == null ? Map.of() : extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(extraContext == null ? Map.of() : extraContext);
        onFlowCompleted(name, attrs, ctx, null);
    }

    @Override
    public void onFlowFailed(
            String name, Throwable error, Map<String, Object> extraAttrs, Map<String, Object> extraContext) {
        var attrs = new LinkedHashMap<String, Object>(extraAttrs == null ? Map.of() : extraAttrs);
        if (error != null) attrs.putIfAbsent("error", error.getClass().getSimpleName());
        var ctx = new LinkedHashMap<String, Object>(extraContext == null ? Map.of() : extraContext);
        onFlowFailed(name, error, attrs, ctx, null);
    }

    @Override
    public void onFlowStarted(
            String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext, TelemetryMeta meta) {
        var attrs = new LinkedHashMap<String, Object>(extraAttrs == null ? Map.of() : extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(extraContext == null ? Map.of() : extraContext);
        if (support != null) {
            FlowEvent holder =
                    FlowEvent.builder().name(name).timestamp(Instant.now()).build();
            holder.attributes().map().putAll(attrs);
            holder.eventContext().putAll(ctx);
            holder.eventContext().put("lifecycle", "STARTED");
            applyMeta(holder, meta);
            support.push(holder);
            support.startNewBatch();
            if (asyncBus != null) asyncBus.dispatch(holder);
        }
        // meta applied to OEvent previously; if needed, augment holder creation to include meta.
    }

    @Override
    public void onFlowCompleted(
            String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext, TelemetryMeta meta) {
        var attrs = new LinkedHashMap<String, Object>(extraAttrs == null ? Map.of() : extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(extraContext == null ? Map.of() : extraContext);
        if (support != null) {
            FlowEvent top = support.currentHolder();
            if (top != null) {
                top.attributes().map().putAll(attrs);
                top.eventContext().putAll(ctx);
                top.eventContext().put("lifecycle", "COMPLETED");
                applyCompletionMeta(top, meta);
            }
            support.clearBatchAfterDispatch();
            support.pop(top);
            if (asyncBus != null && top != null) asyncBus.dispatch(top);
        }
    }

    @Override
    public void onFlowFailed(
            String name,
            Throwable error,
            Map<String, Object> extraAttrs,
            Map<String, Object> extraContext,
            TelemetryMeta meta) {
        var attrs = new LinkedHashMap<String, Object>(extraAttrs == null ? Map.of() : extraAttrs);
        if (error != null) attrs.putIfAbsent("error", error.getClass().getSimpleName());
        var ctx = new LinkedHashMap<String, Object>(extraContext == null ? Map.of() : extraContext);
        if (support != null) {
            FlowEvent top = support.currentHolder();
            if (top != null) {
                top.attributes().map().putAll(attrs);
                top.eventContext().putAll(ctx);
                top.eventContext().put("lifecycle", "FAILED");
                if (error != null) top.setThrowable(error);
                applyCompletionMeta(top, meta);
            }
            support.clearBatchAfterDispatch();
            support.pop(top);
            if (asyncBus != null && top != null) asyncBus.dispatch(top);
        }
    }

    private void applyMeta(FlowEvent holder, TelemetryMeta meta) {
        if (holder == null || meta == null) return;
        if (meta.kind() != null) {
            try {
                holder.kind(SpanKind.valueOf(meta.kind()));
            } catch (Exception ignore) {
                holder.kind(SpanKind.INTERNAL);
            }
        }
        if (meta.traceId() != null || meta.spanId() != null || meta.parentSpanId() != null) {
            holder.trace(meta.traceId(), meta.spanId(), meta.parentSpanId());
        }
        if (meta.tracestate() != null && !meta.tracestate().isBlank()) {
            holder.attributes().put("trace.tracestate", meta.tracestate());
        }
    }

    private void applyCompletionMeta(FlowEvent holder, TelemetryMeta meta) {
        applyMeta(holder, meta);
        if (holder == null || meta == null) return;
        if (meta.statusCode() != null) {
            try {
                StatusCode code = StatusCode.valueOf(meta.statusCode());
                holder.setStatus(new OStatus(code, meta.statusMessage()));
            } catch (Exception ignore) {
                holder.setStatus(new OStatus(StatusCode.UNSET, meta.statusMessage()));
            }
        }
    }
}

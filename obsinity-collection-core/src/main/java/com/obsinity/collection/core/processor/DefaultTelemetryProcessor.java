package com.obsinity.collection.core.processor;

import com.obsinity.collection.core.dispatch.AsyncDispatchBus;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;
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
            TelemetryHolder holder = TelemetryHolder.builder()
                    .name(name)
                    .timestamp(Instant.now())
                    .build();
            holder.attributes().map().putAll(attrs);
            holder.eventContext().putAll(ctx);
            holder.eventContext().put("lifecycle", "STARTED");
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
            TelemetryHolder top = support.currentHolder();
            if (top != null) {
                top.attributes().map().putAll(attrs);
                top.eventContext().putAll(ctx);
                top.eventContext().put("lifecycle", "COMPLETED");
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
            TelemetryHolder top = support.currentHolder();
            if (top != null) {
                top.attributes().map().putAll(attrs);
                top.eventContext().putAll(ctx);
                top.eventContext().put("lifecycle", "FAILED");
            }
            support.clearBatchAfterDispatch();
            support.pop(top);
            if (asyncBus != null && top != null) asyncBus.dispatch(top);
        }
    }
}

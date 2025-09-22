package com.obsinity.collection.core.processor;

import com.obsinity.collection.core.context.TelemetryContext;
import com.obsinity.collection.core.dispatch.DispatchBus;
import com.obsinity.collection.core.model.OEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultTelemetryProcessor implements TelemetryProcessor {
    private final DispatchBus bus;

    public DefaultTelemetryProcessor(DispatchBus bus) {
        this.bus = bus;
    }

    @Override
    public void onFlowStarted(String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext) {
        var attrs = new LinkedHashMap<String, Object>(TelemetryContext.snapshotAttrs());
        if (extraAttrs != null) attrs.putAll(extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(TelemetryContext.snapshotContext());
        if (extraContext != null) ctx.putAll(extraContext);
        bus.dispatch(OEvent.builder()
                .occurredAt(Instant.now())
                .name(name + ":started")
                .attributes(attrs)
                .resourceContext(ctx)
                .build());
    }

    @Override
    public void onFlowCompleted(String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext) {
        var attrs = new LinkedHashMap<String, Object>(TelemetryContext.snapshotAttrs());
        if (extraAttrs != null) attrs.putAll(extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(TelemetryContext.snapshotContext());
        if (extraContext != null) ctx.putAll(extraContext);
        bus.dispatch(OEvent.builder()
                .occurredAt(Instant.now())
                .name(name + ":completed")
                .attributes(attrs)
                .resourceContext(ctx)
                .build());
    }

    @Override
    public void onFlowFailed(
            String name, Throwable error, Map<String, Object> extraAttrs, Map<String, Object> extraContext) {
        var attrs = new LinkedHashMap<String, Object>(TelemetryContext.snapshotAttrs());
        if (extraAttrs != null) attrs.putAll(extraAttrs);
        if (error != null) attrs.putIfAbsent("error", error.getClass().getSimpleName());
        var ctx = new LinkedHashMap<String, Object>(TelemetryContext.snapshotContext());
        if (extraContext != null) ctx.putAll(extraContext);
        bus.dispatch(OEvent.builder()
                .occurredAt(Instant.now())
                .name(name + ":failed")
                .attributes(attrs)
                .resourceContext(ctx)
                .build());
    }

    @Override
    public void onFlowStarted(
            String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext, TelemetryMeta meta) {
        var attrs = new LinkedHashMap<String, Object>(TelemetryContext.snapshotAttrs());
        if (extraAttrs != null) attrs.putAll(extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(TelemetryContext.snapshotContext());
        if (extraContext != null) ctx.putAll(extraContext);
        var b = OEvent.builder()
                .occurredAt(Instant.now())
                .name(name + ":started")
                .attributes(attrs)
                .resourceContext(ctx);
        applyMeta(b, meta);
        bus.dispatch(b.build());
    }

    @Override
    public void onFlowCompleted(
            String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext, TelemetryMeta meta) {
        var attrs = new LinkedHashMap<String, Object>(TelemetryContext.snapshotAttrs());
        if (extraAttrs != null) attrs.putAll(extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(TelemetryContext.snapshotContext());
        if (extraContext != null) ctx.putAll(extraContext);
        var b = OEvent.builder()
                .occurredAt(Instant.now())
                .name(name + ":completed")
                .attributes(attrs)
                .resourceContext(ctx);
        if (meta != null && (meta.statusCode != null || meta.statusMessage != null))
            b.status(meta.statusCode, meta.statusMessage);
        applyMeta(b, meta);
        bus.dispatch(b.build());
    }

    @Override
    public void onFlowFailed(
            String name,
            Throwable error,
            Map<String, Object> extraAttrs,
            Map<String, Object> extraContext,
            TelemetryMeta meta) {
        var attrs = new LinkedHashMap<String, Object>(TelemetryContext.snapshotAttrs());
        if (extraAttrs != null) attrs.putAll(extraAttrs);
        if (error != null) attrs.putIfAbsent("error", error.getClass().getSimpleName());
        var ctx = new LinkedHashMap<String, Object>(TelemetryContext.snapshotContext());
        if (extraContext != null) ctx.putAll(extraContext);
        var b = OEvent.builder()
                .occurredAt(Instant.now())
                .name(name + ":failed")
                .attributes(attrs)
                .resourceContext(ctx);
        if (meta != null && (meta.statusCode != null || meta.statusMessage != null))
            b.status(meta.statusCode, meta.statusMessage);
        applyMeta(b, meta);
        bus.dispatch(b.build());
    }

    private static void applyMeta(OEvent.Builder b, TelemetryMeta meta) {
        if (meta == null) return;
        if (meta.kind != null) b.kind(meta.kind);
        if (meta.domain != null) b.domain(meta.domain);
        if (meta.traceId != null || meta.spanId != null || meta.parentSpanId != null || meta.tracestate != null)
            b.trace(meta.traceId, meta.spanId, meta.parentSpanId, meta.tracestate);
    }
}

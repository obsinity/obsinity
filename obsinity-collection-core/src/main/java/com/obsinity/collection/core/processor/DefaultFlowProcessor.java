package com.obsinity.collection.core.processor;

import com.obsinity.collection.core.dispatch.AsyncDispatchBus;
import com.obsinity.flow.model.FlowEvent;
import com.obsinity.flow.model.OStatus;
import com.obsinity.flow.processor.FlowProcessorSupport;
import com.obsinity.flow.validation.FlowAttributeValidator;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultFlowProcessor implements FlowProcessor {
    public static final String LIFECYCLE = "lifecycle";
    public static final String STARTED = "STARTED";
    public static final String ATTRIBUTES = "attributes";
    public static final String CONTEXT = "context";
    public static final String COMPLETED = "COMPLETED";
    private final AsyncDispatchBus asyncBus;
    private final FlowProcessorSupport support;
    private final FlowAttributeValidator validator;

    public DefaultFlowProcessor(AsyncDispatchBus asyncBus, FlowProcessorSupport support) {
        this(asyncBus, support, null);
    }

    public DefaultFlowProcessor(
            AsyncDispatchBus asyncBus, FlowProcessorSupport support, FlowAttributeValidator validator) {
        this.asyncBus = asyncBus;
        this.support = support;
        this.validator = validator;
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
            String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext, FlowMeta meta) {
        var attrs = new LinkedHashMap<String, Object>(extraAttrs == null ? Map.of() : extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(extraContext == null ? Map.of() : extraContext);
        if (support != null) {
            FlowEvent holder =
                    FlowEvent.builder().name(name).timestamp(Instant.now()).build();
            holder.attributes().map().putAll(attrs);
            holder.eventContext().putAll(ctx);
            holder.eventContext().put(LIFECYCLE, STARTED);
            applyMeta(holder, meta);
            support.push(holder);
            support.startNewBatch();
            if (asyncBus != null) asyncBus.dispatch(holder);
        }
        // meta applied to OEvent previously; if needed, augment holder creation to include meta.
    }

    @Override
    public void onFlowCompleted(
            String name, Map<String, Object> extraAttrs, Map<String, Object> extraContext, FlowMeta meta) {
        var attrs = new LinkedHashMap<String, Object>(extraAttrs == null ? Map.of() : extraAttrs);
        var ctx = new LinkedHashMap<String, Object>(extraContext == null ? Map.of() : extraContext);

        // Validate attributes and context to prevent entities/problematic objects
        if (validator != null) {
            validator.validateMap(attrs, ATTRIBUTES);
            validator.validateMap(ctx, CONTEXT);
        }

        if (support != null) {
            FlowEvent context = support.currentContext();
            if (context != null) {
                context.attributes().map().putAll(attrs);
                context.eventContext().putAll(ctx);
                context.eventContext().put(LIFECYCLE, COMPLETED);
                applyCompletionMeta(context, meta);
            }
            support.clearBatchAfterDispatch();
            support.pop(context);
            if (asyncBus != null && context != null) asyncBus.dispatch(context);
        }
    }

    @Override
    public void onFlowFailed(
            String name,
            Throwable error,
            Map<String, Object> extraAttrs,
            Map<String, Object> extraContext,
            FlowMeta meta) {
        var attrs = new LinkedHashMap<String, Object>(extraAttrs == null ? Map.of() : extraAttrs);
        if (error != null) attrs.putIfAbsent("error", error.getClass().getSimpleName());
        var ctx = new LinkedHashMap<String, Object>(extraContext == null ? Map.of() : extraContext);

        // Validate attributes and context to prevent entities/problematic objects
        if (validator != null) {
            validator.validateMap(attrs, ATTRIBUTES);
            validator.validateMap(ctx, CONTEXT);
        }

        if (support != null) {
            FlowEvent context = support.currentContext();
            if (context != null) {
                context.attributes().map().putAll(attrs);
                context.eventContext().putAll(ctx);
                context.eventContext().put(LIFECYCLE, "FAILED");
                if (error != null) context.setThrowable(error);
                applyCompletionMeta(context, meta);
            }
            support.clearBatchAfterDispatch();
            support.pop(context);
            if (asyncBus != null && context != null) asyncBus.dispatch(context);
        }
    }

    private void applyMeta(FlowEvent context, FlowMeta meta) {
        if (context == null || meta == null) return;
        if (meta.kind() != null) {
            try {
                context.kind(SpanKind.valueOf(meta.kind()));
            } catch (Exception ignore) {
                context.kind(SpanKind.INTERNAL);
            }
        }
        if (meta.traceId() != null || meta.spanId() != null || meta.parentSpanId() != null) {
            context.trace(meta.traceId(), meta.spanId(), meta.parentSpanId());
        }
        if (meta.tracestate() != null && !meta.tracestate().isBlank()) {
            context.attributes().put("trace.tracestate", meta.tracestate());
        }
    }

    private void applyCompletionMeta(FlowEvent context, FlowMeta meta) {
        applyMeta(context, meta);
        if (context == null || meta == null) return;
        if (meta.statusCode() != null) {
            try {
                StatusCode code = StatusCode.valueOf(meta.statusCode());
                context.setStatus(new OStatus(code, meta.statusMessage()));
            } catch (Exception ignore) {
                context.setStatus(new OStatus(StatusCode.UNSET, meta.statusMessage()));
            }
        }
    }
}

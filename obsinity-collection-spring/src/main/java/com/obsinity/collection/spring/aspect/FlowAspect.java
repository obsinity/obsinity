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

/**
 * Spring AOP aspect that intercepts {@link Flow @Flow} and {@link Step @Step} annotations
 * to collect flow telemetry and manage execution context.
 *
 * <p>This aspect provides:
 * <ul>
 *   <li>Flow lifecycle management (start, complete, fail)</li>
 *   <li>Step execution tracking within flows</li>
 *   <li>Attribute and context value extraction</li>
 *   <li>Entity validation (prevents Hibernate/JPA entities)</li>
 *   <li>ThreadLocal cleanup to prevent memory leaks</li>
 *   <li>Configurable enable/disable toggle</li>
 * </ul>
 *
 * <h3>Telemetry Control:</h3>
 * <p>When {@code obsinity.collection.enabled=false}, this aspect bypasses all processing
 * and simply proceeds with method execution, providing zero telemetry overhead.
 *
 * @see Flow
 * @see Step
 * @see FlowProcessor
 * @see org.aspectj.lang.annotation.Aspect
 */
@Aspect
public class FlowAspect {
    public static final String ERROR = "error";
    private final FlowProcessor processor;
    private final FlowProcessorSupport support;
    private final com.obsinity.collection.spring.autoconfigure.ObsinityCollectionProperties properties;

    /**
     * Creates a FlowAspect without configuration properties (legacy constructor).
     * Telemetry will always be enabled when using this constructor.
     *
     * @param processor the flow processor for handling flow events
     * @param support the support utilities for ThreadLocal management
     */
    public FlowAspect(FlowProcessor processor, FlowProcessorSupport support) {
        this(processor, support, null);
    }

    /**
     * Creates a FlowAspect with full configuration support.
     *
     * @param processor the flow processor for handling flow events
     * @param support the support utilities for ThreadLocal management
     * @param properties configuration properties (if null, telemetry always enabled)
     */
    public FlowAspect(
            FlowProcessor processor,
            FlowProcessorSupport support,
            com.obsinity.collection.spring.autoconfigure.ObsinityCollectionProperties properties) {
        this.processor = processor;
        this.support = support;
        this.properties = properties;
    }

    /**
     * Intercepts methods annotated with {@link Flow @Flow} to manage flow lifecycle and telemetry.
     *
     * <p>This advice handles the complete flow lifecycle:
     * <ol>
     *   <li>Extracts flow metadata (name, attributes, trace context)</li>
     *   <li>Notifies processor of flow start</li>
     *   <li>Executes the target method</li>
     *   <li>Captures return value (if applicable)</li>
     *   <li>Notifies processor of completion or failure</li>
     *   <li>Cleans up ThreadLocals for root flows (memory leak prevention)</li>
     * </ol>
     *
     * <h3>Telemetry Control:</h3>
     * <p>When {@code obsinity.collection.enabled=false}, this method immediately proceeds
     * with method execution, bypassing all telemetry processing for zero overhead.
     *
     * <h3>Root Flow Detection:</h3>
     * <p>A "root flow" is a flow with no parent (first flow in the call stack).
     * Root flows are responsible for ThreadLocal cleanup to prevent memory leaks in
     * thread pool environments.
     *
     * <h3>Exception Handling:</h3>
     * <p>All exceptions are caught, recorded in telemetry, and re-thrown to preserve
     * normal application behavior. The finally block ensures cleanup happens even on errors.
     *
     * @param pjp the AspectJ proceeding join point containing method execution context
     * @return the result of the intercepted method execution
     * @throws Throwable any exception thrown by the intercepted method
     *
     * @see Flow
     * @see FlowProcessor#onFlowStarted
     * @see FlowProcessor#onFlowCompleted
     * @see FlowProcessor#onFlowFailed
     */
    @Around("@annotation(com.obsinity.collection.api.annotations.Flow) && execution(* *(..))")
    public Object aroundFlow(ProceedingJoinPoint pjp) throws Throwable {
        // Early exit: If telemetry is disabled globally, bypass all processing
        // This check must be first to minimize overhead when telemetry is off
        if (properties != null && !properties.isEnabled()) {
            return pjp.proceed();
        }

        // Extract flow name from @Flow annotation or fallback to method signature
        String name = resolveFlowName(pjp);

        // Extract attributes (@PushAttribute) and context (@PushContextValue) from parameters
        // This also validates that no Hibernate/JPA entities are passed
        AttrCtx ac = AttributeParamExtractor.extract(pjp);

        // Build metadata including trace context (traceId, spanId, etc.) from MDC
        FlowMeta meta = buildMeta(pjp, null);

        // Check if method returns void - affects whether we capture return value
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        boolean returnsVoid = ms.getReturnType() == Void.TYPE;

        // Determine if this is a root-level flow (no parent flow in the call stack)
        // Root flows are responsible for cleaning up ThreadLocals to prevent memory leaks
        boolean isRootFlow = (support != null && support.currentContext() == null);

        // Notify processor that the flow is starting
        // This pushes a new FlowEvent onto the ThreadLocal stack
        processor.onFlowStarted(name, ac.attributes(), ac.context(), meta);

        try {
            // Execute the actual business method
            Object result = pjp.proceed();

            // Capture return value for telemetry (unless method returns void)
            // Return values can be useful for debugging and auditing
            if (!returnsVoid && support != null) {
                FlowEvent context = support.currentContext();
                if (context != null) {
                    context.setReturnValue(result);
                }
            }

            // Build completion metadata with OK status
            FlowMeta ok = buildMeta(pjp, new StatusHint("OK", null));

            // Notify processor of successful completion
            // This pops the FlowEvent from the ThreadLocal stack and dispatches it
            processor.onFlowCompleted(name, ac.attributes(), ac.context(), ok);

            return result;

        } catch (Throwable t) {
            // Exception path: method execution failed

            // Clear any return value that might have been set
            if (support != null) {
                FlowEvent context = support.currentContext();
                if (context != null) {
                    context.clearReturnValue();
                }
            }

            // Create a copy of attributes and add error information
            var attrs = new java.util.LinkedHashMap<String, Object>(ac.attributes());
            attrs.put(ERROR, t.toString());

            // Build failure metadata with ERROR status and exception message
            FlowMeta err = buildMeta(pjp, new StatusHint("ERROR", t.getMessage()));

            // Notify processor of flow failure
            // This captures the exception for telemetry and pops the FlowEvent
            processor.onFlowFailed(name, t, attrs, ac.context(), err);

            // Re-throw to preserve application behavior
            throw t;

        } finally {
            // Critical cleanup: ThreadLocal memory leak prevention
            // Only root flows perform cleanup - nested flows rely on their root
            // This ensures ThreadLocals are cleared when the outermost flow completes,
            // preventing memory leaks in thread pool environments (e.g., web servers)
            if (isRootFlow && support != null) {
                support.cleanupThreadLocals();
            }
        }
    }

    /**
     * Intercepts methods annotated with {@link Step @Step} to track step execution within flows.
     *
     * <p>Steps represent sub-operations within a flow and are recorded as nested events.
     * This advice handles two scenarios:
     * <ol>
     *   <li><b>In-Flow Step:</b> Step executes within an active flow - recorded as nested event</li>
     *   <li><b>Orphan Step:</b> Step executes without an active flow - auto-promoted to a flow</li>
     * </ol>
     *
     * <h3>Orphan Step Handling:</h3>
     * <p>An "orphan step" is a {@code @Step} method called without an active {@code @Flow}.
     * This typically indicates a programming error. The aspect logs a warning (configurable
     * via {@link OrphanAlert @OrphanAlert}) and auto-promotes the step to a flow to avoid
     * losing telemetry data.
     *
     * <h3>In-Flow Step Handling:</h3>
     * <p>When a step executes within an active flow, it's recorded as a nested event with
     * start/end timestamps, attributes, and error information (if applicable). This allows
     * detailed timing analysis of sub-operations within a flow.
     *
     * <h3>Telemetry Control:</h3>
     * <p>When {@code obsinity.collection.enabled=false}, this method immediately proceeds
     * with method execution, bypassing all step tracking for zero overhead.
     *
     * @param pjp the AspectJ proceeding join point containing method execution context
     * @return the result of the intercepted method execution
     * @throws Throwable any exception thrown by the intercepted method
     *
     * @see Step
     * @see OrphanAlert
     * @see FlowEvent#beginStepEvent
     * @see FlowEvent#endStepEvent
     */
    @Around("@annotation(com.obsinity.collection.api.annotations.Step) && execution(* *(..))")
    public Object aroundStep(ProceedingJoinPoint pjp) throws Throwable {
        // Early exit: If telemetry is disabled globally, bypass all processing
        if (properties != null && !properties.isEnabled()) {
            return pjp.proceed();
        }

        // Extract step name from @Step annotation or fallback to method signature
        String name = resolveStepName(pjp);

        // Extract attributes and context from parameters (with entity validation)
        AttrCtx ac = AttributeParamExtractor.extract(pjp);

        // Check if there's an active flow on the current thread
        // If null, this is an "orphan step" (step without parent flow)
        FlowEvent context = (support != null) ? support.currentContext() : null;

        if (context == null) {
            // Orphan step path: no active flow, auto-promote to flow
            return handleOrphanStep(pjp, name, ac);
        }

        // Normal step path: execute within the active flow
        return handleInFlowStep(pjp, name, ac, context);
    }

    /**
     * Handles an orphan step (step with no active flow) by auto-promoting it to a flow.
     *
     * <p>An orphan step occurs when a {@code @Step} method is called without an active
     * {@code @Flow} on the current thread. This typically indicates:
     * <ul>
     *   <li>Missing {@code @Flow} annotation on the calling method</li>
     *   <li>Step called from untracked code path (e.g., scheduled task, async thread)</li>
     *   <li>Incorrect nesting of flow/step annotations</li>
     * </ul>
     *
     * <h3>Auto-Promotion Strategy:</h3>
     * <p>Rather than failing silently or throwing an exception, the aspect auto-promotes
     * the orphan step to a full flow. This ensures telemetry data is not lost while still
     * alerting developers to the issue via logging.
     *
     * <h3>Logging Behavior:</h3>
     * <p>The warning level is controlled by the {@link OrphanAlert @OrphanAlert} annotation:
     * <ul>
     *   <li>{@link OrphanAlert.Level#ERROR ERROR} - Logs at ERROR level (default)</li>
     *   <li>{@link OrphanAlert.Level#WARN WARN} - Logs at WARN level</li>
     * </ul>
     *
     * @param pjp the proceeding join point for the orphan step method
     * @param name the resolved name of the step
     * @param ac the extracted attributes and context from parameters
     * @return the result of the step method execution
     * @throws Throwable any exception thrown by the step method
     */
    private Object handleOrphanStep(ProceedingJoinPoint pjp, String name, AttrCtx ac) throws Throwable {
        // Check for @OrphanAlert annotation to determine logging level
        OrphanAlert oa = ((MethodSignature) pjp.getSignature()).getMethod().getAnnotation(OrphanAlert.class);

        // Log warning about orphan step (configurable level via @OrphanAlert)
        if (support != null) {
            support.logOrphanStep(name, oa != null ? oa.value() : OrphanAlert.Level.ERROR);
        }

        try {
            // Auto-promote step to flow: start the flow
            processor.onFlowStarted(name, ac.attributes(), ac.context(), buildMeta(pjp, null));

            // Execute the step method
            Object result = pjp.proceed();

            // Complete the promoted flow successfully
            processor.onFlowCompleted(name, ac.attributes(), ac.context(), buildMeta(pjp, new StatusHint("OK", null)));

            return result;

        } catch (Throwable t) {
            // Exception path: capture error in the promoted flow

            // Add error class name to attributes for telemetry
            Map<String, Object> attrs = new LinkedHashMap<>(ac.attributes());
            attrs.putIfAbsent(ERROR, t.getClass().getSimpleName());

            // Fail the promoted flow with error details
            processor.onFlowFailed(
                    name, t, attrs, ac.context(), buildMeta(pjp, new StatusHint("ERROR", t.getMessage())));

            // Re-throw to preserve application behavior
            throw t;

        } finally {
            // Critical: Clean up ThreadLocals since orphan step was promoted to root flow
            // Orphan steps become root flows and must perform cleanup to prevent memory leaks
            if (support != null) {
                support.cleanupThreadLocals();
            }
        }
    }

    /**
     * Handles a step within an active flow by recording it as a nested event.
     *
     * <p>When a {@code @Step} method executes within an active {@code @Flow}, it's recorded
     * as a nested event within the flow's event timeline. This provides detailed visibility
     * into sub-operations and their timing.
     *
     * <h3>Step Event Recording:</h3>
     * <p>Each step creates a nested event with:
     * <ul>
     *   <li><b>Start timestamp:</b> Captured before execution (both epoch and monotonic)</li>
     *   <li><b>Attributes:</b> From {@code @PushAttribute} parameters</li>
     *   <li><b>Context:</b> From {@code @PushContextValue} parameters</li>
     *   <li><b>Kind:</b> From {@code @Kind} annotation or defaults to INTERNAL</li>
     *   <li><b>Duration:</b> Calculated from start/end timestamps</li>
     *   <li><b>Error info:</b> Exception class name if step fails</li>
     * </ul>
     *
     * <h3>Attribute Merging:</h3>
     * <p>Step attributes and context are merged into the parent flow's context.
     * This allows steps to contribute additional telemetry data to the overall flow.
     *
     * <h3>Timing Precision:</h3>
     * <p>Uses both {@code System.nanoTime()} (monotonic, for duration) and epoch nanos
     * (wall-clock time, for absolute timestamps) to ensure accurate timing even across
     * clock adjustments.
     *
     * @param pjp the proceeding join point for the step method
     * @param name the resolved name of the step
     * @param ac the extracted attributes and context from parameters
     * @param context the active flow event context (guaranteed non-null)
     * @return the result of the step method execution
     * @throws Throwable any exception thrown by the step method
     */
    private Object handleInFlowStep(ProceedingJoinPoint pjp, String name, AttrCtx ac, FlowEvent context)
            throws Throwable {
        // Merge step attributes into parent flow's attribute map
        // This allows steps to add telemetry data to the overall flow
        context.attributes().map().putAll(ac.attributes());

        // Merge step context into parent flow's event context
        context.eventContext().putAll(ac.context());

        // Capture step start time using monotonic clock (System.nanoTime)
        // Monotonic time is immune to clock adjustments and provides accurate duration
        long startNano = System.nanoTime();

        // Also capture wall-clock time (epoch nanos) for absolute timestamp
        long startEpochNanos = support != null ? support.unixNanos(Instant.now()) : 0L;

        // Create snapshot of initial attributes for the step event
        OAttributes initial = new OAttributes(new LinkedHashMap<>(ac.attributes()));

        // Extract step kind from @Kind annotation or use default (INTERNAL)
        String kindValue = extractStepKind(pjp);

        // Begin the step event - adds nested event to the flow's event list
        context.beginStepEvent(name, startEpochNanos, startNano, initial, kindValue);

        try {
            // Execute the step method
            Object result = pjp.proceed();

            // End the step event successfully (no updates to attributes)
            // Captures end timestamp and calculates duration
            context.endStepEvent(support != null ? support.unixNanos(Instant.now()) : 0L, System.nanoTime(), null);

            return result;

        } catch (Throwable t) {
            // Exception path: step execution failed

            // Create attribute updates with error information
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(ERROR, t.getClass().getSimpleName());

            // End the step event with error attributes
            // This marks the step as failed in telemetry
            context.endStepEvent(support != null ? support.unixNanos(Instant.now()) : 0L, System.nanoTime(), updates);

            // Re-throw exception to preserve application behavior
            throw t;
        }
    }

    /**
     * Extracts the SpanKind from {@link Kind @Kind} annotation or defaults to INTERNAL.
     *
     * <p>The kind indicates the type of operation (e.g., SERVER, CLIENT, PRODUCER, CONSUMER).
     * This aligns with OpenTelemetry's SpanKind concept for consistent distributed tracing.
     *
     * @param pjp the proceeding join point
     * @return the kind name (e.g., "SERVER", "CLIENT", "INTERNAL")
     */
    private static String extractStepKind(ProceedingJoinPoint pjp) {
        Kind stepKind = ((MethodSignature) pjp.getSignature()).getMethod().getAnnotation(Kind.class);
        return (stepKind != null && stepKind.value() != null) ? stepKind.value().name() : SpanKind.INTERNAL.name();
    }

    /**
     * Resolves the flow name from {@link Flow @Flow} annotation or falls back to method signature.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>@Flow(name = "explicit.name") - explicit name attribute</li>
     *   <li>@Flow("value.name") - value attribute (shorthand)</li>
     *   <li>Method signature - e.g., "MyClass.myMethod(..)" (fallback)</li>
     * </ol>
     *
     * @param pjp the proceeding join point
     * @return the resolved flow name
     */
    private static String resolveFlowName(ProceedingJoinPoint pjp) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method m = ms.getMethod();
        Flow f = m.getAnnotation(Flow.class);
        if (f != null) {
            // Try explicit name attribute first
            if (!f.name().isBlank()) return f.name();
            // Try value attribute (shorthand syntax)
            if (!f.value().isBlank()) return f.value();
        }
        // Fallback: use method signature (e.g., "MyService.processOrder(..)")
        return ms.toShortString();
    }

    /**
     * Resolves the step name from {@link Step @Step} annotation or falls back to method signature.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>@Step(name = "explicit.name") - explicit name attribute</li>
     *   <li>@Step("value.name") - value attribute (shorthand)</li>
     *   <li>Method signature - e.g., "MyClass.myMethod(..)" (fallback)</li>
     * </ol>
     *
     * @param pjp the proceeding join point
     * @return the resolved step name
     */
    private static String resolveStepName(ProceedingJoinPoint pjp) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method m = ms.getMethod();
        Step s = m.getAnnotation(Step.class);
        if (s != null) {
            // Try explicit name attribute first
            if (!s.name().isBlank()) return s.name();
            // Try value attribute (shorthand syntax)
            if (!s.value().isBlank()) return s.value();
        }
        // Fallback: use method signature (e.g., "MyService.validateData(..)")
        return ms.toShortString();
    }

    /**
     * Internal record to pass status information (code and message) between methods.
     *
     * @param code the status code (e.g., "OK", "ERROR")
     * @param message optional status message (e.g., exception message)
     */
    private record StatusHint(String code, String message) {}

    /**
     * Builds FlowMeta containing metadata for the flow/step execution.
     *
     * <p>FlowMeta includes:
     * <ul>
     *   <li><b>Kind:</b> Operation type from {@link Kind @Kind} annotation (e.g., SERVER, CLIENT)</li>
     *   <li><b>Status:</b> Execution status (OK, ERROR) with optional message</li>
     *   <li><b>Trace Context:</b> Distributed tracing IDs from MDC (traceId, spanId, etc.)</li>
     * </ul>
     *
     * <h3>Trace Context Extraction:</h3>
     * <p>Attempts to extract distributed tracing context from SLF4J MDC, supporting:
     * <ul>
     *   <li>W3C Trace Context (traceparent header)</li>
     *   <li>B3 single header format (Zipkin)</li>
     *   <li>B3 multi-header format (X-B3-TraceId, X-B3-SpanId, etc.)</li>
     *   <li>Direct MDC keys (traceId, spanId, parentSpanId)</li>
     * </ul>
     *
     * @param pjp the proceeding join point for accessing method annotations
     * @param status optional status hint (code and message) for the flow/step
     * @return built FlowMeta with all available metadata
     */
    private static FlowMeta buildMeta(ProceedingJoinPoint pjp, StatusHint status) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method m = ms.getMethod();
        FlowMeta.Builder b = FlowMeta.builder();

        // Set kind from @Kind annotation if present (e.g., SERVER, CLIENT, INTERNAL)
        Kind k = m.getAnnotation(Kind.class);
        if (k != null && k.value() != null) {
            b.kind(k.value().name());
        }

        // Set execution status (OK for success, ERROR for failure)
        if (status != null) {
            b.status(status.code(), status.message());
        }

        // Extract distributed tracing context from MDC (if available)
        // Supports W3C, B3, and custom trace propagation formats
        TraceContext trace = extractTraceContextFromMdc();
        if (trace.hasAnyValue()) {
            b.trace(trace.traceId, trace.spanId, trace.parentSpanId, trace.tracestate);
        }

        return b.build();
    }

    /**
     * Container for distributed tracing context extracted from MDC.
     *
     * <p>Holds trace propagation fields used for distributed tracing across services:
     * <ul>
     *   <li><b>traceId:</b> Unique ID for the entire distributed trace</li>
     *   <li><b>spanId:</b> Unique ID for this specific operation</li>
     *   <li><b>parentSpanId:</b> ID of the parent operation (for nested spans)</li>
     *   <li><b>tracestate:</b> Vendor-specific trace state (W3C Trace Context)</li>
     * </ul>
     *
     * @param traceId the trace identifier (typically 128-bit in hex)
     * @param spanId the span identifier (typically 64-bit in hex)
     * @param parentSpanId the parent span identifier (nullable)
     * @param tracestate vendor-specific trace state (nullable)
     */
    private record TraceContext(String traceId, String spanId, String parentSpanId, String tracestate) {
        /**
         * Checks if any trace context values are present.
         *
         * @return true if at least one field is non-null, false if all are null
         */
        boolean hasAnyValue() {
            return traceId != null || spanId != null || parentSpanId != null || tracestate != null;
        }
    }

    /**
     * Extracts distributed tracing context from SLF4J MDC (Mapped Diagnostic Context).
     *
     * <p>This method attempts to extract trace propagation information from various
     * formats commonly used in distributed tracing systems. It tries multiple strategies
     * in order of preference, using the first available value for each field.
     *
     * <h3>Supported Trace Formats:</h3>
     * <ol>
     *   <li><b>W3C Trace Context:</b> Standard traceparent header
     *       <br>Example: {@code 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01}</li>
     *   <li><b>B3 Single Header:</b> Zipkin's compact format
     *       <br>Example: {@code 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1}</li>
     *   <li><b>Direct MDC Keys:</b> Simple key-value pairs
     *       <br>Keys: traceId, spanId, parentSpanId, tracestate</li>
     *   <li><b>B3 Multi-Header:</b> Zipkin's multi-header format
     *       <br>Headers: X-B3-TraceId, X-B3-SpanId, X-B3-ParentSpanId</li>
     * </ol>
     *
     * <h3>Extraction Strategy:</h3>
     * <p>Values are extracted using a "coalesce" strategy: once a value is found,
     * it's not overwritten by subsequent extraction attempts. This ensures priority
     * is given to more specific formats (W3C) over generic ones (direct MDC keys).
     *
     * <h3>Error Handling:</h3>
     * <p>All exceptions are caught and ignored because:
     * <ul>
     *   <li>MDC may not be available in all environments (e.g., non-web applications)</li>
     *   <li>SecurityManager may restrict MDC access in some contexts</li>
     *   <li>Missing trace context is acceptable - telemetry still works without it</li>
     * </ul>
     *
     * @return TraceContext with extracted values, or empty context if extraction fails
     */
    private static TraceContext extractTraceContextFromMdc() {
        try {
            // Initialize all fields as null - will be populated from various sources
            String traceId = null;
            String spanId = null;
            String parentSpanId = null;
            String tracestate = null;

            // Strategy 1: Try W3C traceparent format first (most standardized)
            // Format: version-traceId-spanId-flags
            String traceparent = org.slf4j.MDC.get("traceparent");
            if (traceparent != null) {
                String[] parsed = parseTraceparent(traceparent);
                if (parsed.length > 0) {
                    traceId = parsed[0];
                    spanId = parsed[1];
                }
            }

            // Strategy 2: Try B3 single header format (Zipkin's compact format)
            // Format: traceId-spanId-sampled-parentSpanId
            String b3 = org.slf4j.MDC.get("b3");
            if (b3 != null) {
                String[] b3parsed = parseB3Single(b3);
                if (b3parsed.length > 0) {
                    // Use coalesce to preserve already-found values
                    traceId = coalesce(traceId, b3parsed[0]);
                    spanId = coalesce(spanId, b3parsed[1]);
                    parentSpanId = coalesce(parentSpanId, b3parsed[2]);
                }
            }

            // Strategy 3: Try direct MDC keys (simple key-value pairs)
            // These are fallbacks or may be set by custom instrumentation
            traceId = coalesce(traceId, org.slf4j.MDC.get("traceId"));
            spanId = coalesce(spanId, org.slf4j.MDC.get("spanId"));
            parentSpanId = coalesce(parentSpanId, org.slf4j.MDC.get("parentSpanId"));
            tracestate = coalesce(tracestate, org.slf4j.MDC.get("tracestate"));

            // Strategy 4: Try B3 multi-header format (Zipkin's multi-header format)
            // Uses separate MDC keys for each field
            traceId = coalesce(traceId, org.slf4j.MDC.get("X-B3-TraceId"));
            spanId = coalesce(spanId, org.slf4j.MDC.get("X-B3-SpanId"));
            parentSpanId = coalesce(parentSpanId, org.slf4j.MDC.get("X-B3-ParentSpanId"));

            return new TraceContext(traceId, spanId, parentSpanId, tracestate);

        } catch (Throwable ignore) {
            // Ignore: MDC may not be available in all environments (e.g., non-web contexts)
            // or may throw SecurityException in restricted environments. Return empty context.
            return new TraceContext(null, null, null, null);
        }
    }

    /**
     * Parses W3C traceparent header format: version-traceId-spanId-flags
     * Example: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
     *
     * @param tp the traceparent header value
     * @return array with [traceId, spanId] or empty array if invalid
     */
    private static String[] parseTraceparent(String tp) {
        try {
            String s = tp.trim().toLowerCase(java.util.Locale.ROOT);
            String[] parts = s.split("-", -1);
            if (parts.length < 4) {
                return new String[0]; // Invalid format
            }
            String traceId = parts[1];
            String spanId = parts[2];
            if (traceId.length() == 32 && spanId.length() == 16) {
                return new String[] {traceId, spanId};
            }
        } catch (Exception ignore) {
            // Ignore: Malformed traceparent string (e.g., encoding issues, unexpected format).
            // Returning empty array allows graceful degradation without logging noise.
        }
        return new String[0];
    }

    /**
     * Returns the first non-null, non-blank string, or null if both are blank/null.
     *
     * @param a first string to check
     * @param b fallback string
     * @return first non-blank string or null
     */
    private static String coalesce(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    /**
     * Parses B3 single header format: traceId-spanId-sampled-parentSpanId
     * Example: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1-05e3ac9a4f6e3b90
     *
     * @param b3 the B3 single header value
     * @return array with [traceId, spanId, parentSpanId] or empty array if invalid
     */
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
            // Ignore: Malformed B3 header string. Returning empty array allows
            // fallback to other trace extraction methods without failing the entire flow.
        }
        return new String[0];
    }
}

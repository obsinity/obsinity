package com.obsinity.collection.api.annotations;

import java.lang.annotation.*;

/**
 * Marks a method as the entry point of a telemetry flow.
 *
 * <p>The {@code FlowAspect} wraps the annotated method, emitting a {@code FlowEvent} that
 * carries attributes, context values, trace metadata, elapsed time, and runtime status.
 * Nested {@code @Flow} methods form parent/child flows on the same thread-local stack.
 *
 * <h3>Naming</h3>
 * <p>Resolution order:
 * <ol>
 *   <li>{@link #name()} — explicit name attribute</li>
 *   <li>{@link #value()} — shorthand value attribute</li>
 *   <li>Method signature — fallback (e.g. {@code "MyService.createOrder(..)"} )</li>
 * </ol>
 *
 * <h3>⚠️ Controller methods — NOT supported</h3>
 * <p>{@code @Flow} must <strong>not</strong> be placed directly on
 * {@code @Controller} / {@code @RestController} methods. The AOP advice fires and
 * dispatches the {@code FlowEvent} before Spring MVC commits the HTTP response, so:
 * <ul>
 *   <li>The HTTP response status code is not captured.</li>
 *   <li>HTTP request metadata (method, URI, route template) is not available.</li>
 *   <li>Any HTTP status remapped by {@code @ControllerAdvice} is silently missed.</li>
 * </ul>
 * <p>Place {@code @Flow} on the <strong>service layer method</strong> called by the controller
 * instead. See {@code documentation/controller-flow-gap.md} for the full analysis and the
 * options being considered to resolve this limitation.
 *
 * @see Step
 * @see PushAttribute
 * @see PushContextValue
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Flow {
    String value() default "";

    String name() default "";
}

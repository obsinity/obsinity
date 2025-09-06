package com.obsinity.client.api.annotations;

import io.opentelemetry.api.trace.SpanKind;
import java.lang.annotation.*;

@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Kind {
    SpanKind value() default SpanKind.INTERNAL;
}

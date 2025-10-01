package com.obsinity.collection.api.annotations;

import io.opentelemetry.api.trace.SpanKind;
import java.lang.annotation.*;

@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Kind {
    SpanKind value() default SpanKind.INTERNAL;
}

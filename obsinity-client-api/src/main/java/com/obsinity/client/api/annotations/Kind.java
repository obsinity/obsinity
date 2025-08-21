package com.obsinity.client.api.annotations;

import java.lang.annotation.*;
import io.opentelemetry.api.trace.SpanKind;

@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Kind {
  SpanKind value() default SpanKind.INTERNAL;
}

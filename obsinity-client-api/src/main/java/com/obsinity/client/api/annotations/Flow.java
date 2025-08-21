package com.obsinity.client.api.annotations;

import java.lang.annotation.*;

@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Flow {
  String value() default "";
  String name() default "";
}

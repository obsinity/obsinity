package com.obsinity.client.api.annotations;

import java.lang.annotation.*;

@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PushContextValue {
    String value() default "";

    String name() default "";
}

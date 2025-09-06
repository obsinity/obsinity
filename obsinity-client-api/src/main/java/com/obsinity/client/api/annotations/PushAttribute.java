package com.obsinity.client.api.annotations;

import java.lang.annotation.*;

@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PushAttribute {
    String value() default "";

    String name() default "";

    boolean omitIfNull() default true;
}

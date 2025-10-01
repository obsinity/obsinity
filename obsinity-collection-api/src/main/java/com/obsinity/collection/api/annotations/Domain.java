package com.obsinity.collection.api.annotations;

import java.lang.annotation.*;

@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Domain {
    /** Free-form domain string (e.g., "http", "db", "messaging"). */
    String value() default "";

    /**
     * Optional enum for common domains. If set to anything other than CUSTOM,
     * it takes precedence over the string value.
     */
    Type type() default Type.CUSTOM;

    enum Type {
        CUSTOM,
        HTTP,
        MESSAGING,
        DB,
        RPC,
        INTERNAL
    }
}

package com.obsinity.collection.api.annotations;

import java.lang.annotation.*;

@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OrphanAlert {
    Level value() default Level.WARN;

    enum Level {
        INFO,
        WARN,
        ERROR
    }
}

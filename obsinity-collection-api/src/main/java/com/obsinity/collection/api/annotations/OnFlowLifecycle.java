package com.obsinity.collection.api.annotations;

import java.lang.annotation.*;

@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnFlowLifecycle {
    Lifecycle value();

    enum Lifecycle {
        STARTED,
        COMPLETED,
        FAILED
    }
}

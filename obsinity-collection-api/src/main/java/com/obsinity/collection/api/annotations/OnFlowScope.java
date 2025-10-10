package com.obsinity.collection.api.annotations;

import java.lang.annotation.*;

@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(OnFlowScopes.class)
public @interface OnFlowScope {
    String value();
}

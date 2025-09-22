package com.obsinity.collection.api.annotations;

import java.lang.annotation.*;

@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PullAttribute {
    String value();
}

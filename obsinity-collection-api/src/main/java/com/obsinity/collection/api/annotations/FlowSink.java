package com.obsinity.collection.api.annotations;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface FlowSink {}

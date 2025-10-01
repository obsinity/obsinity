package com.obsinity.client.core;

import com.obsinity.collection.spring.autoconfigure.CollectionAutoConfiguration;
import com.obsinity.collection.spring.autoconfigure.HandlerAutoConfiguration;
import com.obsinity.collection.spring.autoconfigure.TelemetrySupportAutoConfiguration;
import com.obsinity.collection.spring.autoconfigure.TraceAutoConfiguration;
import com.obsinity.collection.spring.autoconfigure.WebfluxTraceAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Annotate your Spring Boot application with this to enable Obsinity client telemetry.
 * - Turns on AspectJ proxies for the AOP aspect capturing @Flow/@Step
 * - Imports core auto-config for collection and trace propagation
 */
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
@ImportAutoConfiguration({
        CollectionAutoConfiguration.class,
        TelemetrySupportAutoConfiguration.class,
        TraceAutoConfiguration.class,
        WebfluxTraceAutoConfiguration.class,
        HandlerAutoConfiguration.class
})
public @interface ObsinityApplication {}

package com.obsinity.collection.spring.annotation;

import com.obsinity.collection.spring.autoconfigure.CollectionAutoConfiguration;
import com.obsinity.collection.spring.autoconfigure.HandlerAutoConfiguration;
import com.obsinity.collection.spring.autoconfigure.TelemetrySupportAutoConfiguration;
import com.obsinity.collection.spring.autoconfigure.TraceAutoConfiguration;
import com.obsinity.collection.spring.autoconfigure.WebfluxTraceAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Convenience meta-annotation to enable Obsinity client-side telemetry in Spring apps.
 * - Enables AspectJ proxies for the AOP aspect that captures @Flow/@Step.
 * - Imports core auto-configurations for the collection SDK and trace propagation filters.
 */
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
@ImportAutoConfiguration({
        CollectionAutoConfiguration.class,
        TelemetrySupportAutoConfiguration.class,
        TraceAutoConfiguration.class,
        WebfluxTraceAutoConfiguration.class,
        HandlerAutoConfiguration.class
})
public @interface EnableObsinityTelemetry {}


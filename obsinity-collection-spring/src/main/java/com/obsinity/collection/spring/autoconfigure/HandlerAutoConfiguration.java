package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.receivers.FlowSinkHandler;
import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.collection.spring.scanner.TelemetryFlowSinkScanner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class HandlerAutoConfiguration {

    @Bean
    public TelemetryHandlerRegistry telemetryHandlerRegistry() {
        return new TelemetryHandlerRegistry();
    }

    @Bean
    public org.springframework.beans.factory.config.BeanPostProcessor flowSinkHandlerRegistrar(
            TelemetryHandlerRegistry registry) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FlowSinkHandler sink) registry.register(sink);
                return bean;
            }
        };
    }

    @Bean
    public TelemetryFlowSinkScanner telemetryFlowSinkScanner(TelemetryHandlerRegistry registry) {
        return new TelemetryFlowSinkScanner(registry);
    }
}

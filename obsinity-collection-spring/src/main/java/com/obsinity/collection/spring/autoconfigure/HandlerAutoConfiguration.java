package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.receivers.EventHandler;
import com.obsinity.collection.core.receivers.HandlerRegistry;
import com.obsinity.collection.spring.scanner.TelemetryEventHandlerScanner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class HandlerAutoConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry() {
        return new HandlerRegistry();
    }

    @Bean
    public TelemetryEventHandlerScanner telemetryEventHandlerScanner(HandlerRegistry registry) {
        return new TelemetryEventHandlerScanner(registry);
    }

    @Bean
    public org.springframework.beans.factory.config.BeanPostProcessor eventHandlerBeanRegistrar(HandlerRegistry registry) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof EventHandler eh) registry.register(eh);
                return bean;
            }
        };
    }
}

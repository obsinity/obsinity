package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.sinks.FlowHandlerRegistry;
import com.obsinity.collection.core.sinks.FlowSinkHandler;
import com.obsinity.collection.spring.scanner.FlowSinkScanner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class HandlerAutoConfiguration {

    @Bean
    public FlowHandlerRegistry flowHandlerRegistry() {
        return new FlowHandlerRegistry();
    }

    @Bean
    public org.springframework.beans.factory.config.BeanPostProcessor flowSinkHandlerRegistrar(
            FlowHandlerRegistry registry) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FlowSinkHandler sink) registry.register(sink);
                return bean;
            }
        };
    }

    @Bean
    public FlowSinkScanner flowSinkScanner(FlowHandlerRegistry registry) {
        return new FlowSinkScanner(registry);
    }
}

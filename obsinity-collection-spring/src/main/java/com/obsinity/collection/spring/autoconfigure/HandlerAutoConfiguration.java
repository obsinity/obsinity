package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.sinks.FlowHandlerRegistry;
import com.obsinity.collection.core.sinks.FlowSinkHandler;
import com.obsinity.collection.spring.scanner.FlowSinkScanner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

@AutoConfiguration
public class HandlerAutoConfiguration {

    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @Bean
    public FlowHandlerRegistry flowHandlerRegistry() {
        return new FlowHandlerRegistry();
    }

    @Bean
    public static BeanPostProcessor flowSinkHandlerRegistrar(ObjectProvider<FlowHandlerRegistry> registryProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FlowSinkHandler sink) {
                    FlowHandlerRegistry registry = registryProvider.getObject();
                    registry.register(sink);
                }
                return bean;
            }
        };
    }

    @Bean
    public static FlowSinkScanner flowSinkScanner(ObjectProvider<FlowHandlerRegistry> registryProvider) {
        return new FlowSinkScanner(registryProvider);
    }
}

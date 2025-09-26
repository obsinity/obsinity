package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.collection.core.receivers.TelemetryReceiver;
import com.obsinity.collection.spring.scanner.TelemetryHolderReceiverScanner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class HandlerAutoConfiguration {

    @Bean
    public TelemetryHandlerRegistry telemetryHandlerRegistry() {
        return new TelemetryHandlerRegistry();
    }

    @Bean
    public org.springframework.beans.factory.config.BeanPostProcessor telemetryReceiverBeanRegistrar(
            TelemetryHandlerRegistry registry) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof TelemetryReceiver tr) registry.register(tr);
                return bean;
            }
        };
    }

    @Bean
    public TelemetryHolderReceiverScanner telemetryHolderReceiverScanner(TelemetryHandlerRegistry registry) {
        return new TelemetryHolderReceiverScanner(registry);
    }
}

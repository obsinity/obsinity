package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.receivers.HandlerRegistry;
import com.obsinity.collection.core.receivers.HandlerSink;
import com.obsinity.collection.core.sink.EventSink;
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
    public EventSink handlerSink(HandlerRegistry registry) {
        return new HandlerSink(registry);
    }

    @Bean
    public TelemetryEventHandlerScanner telemetryEventHandlerScanner(HandlerRegistry registry) {
        return new TelemetryEventHandlerScanner(registry);
    }
}

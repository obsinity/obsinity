package com.obsinity.collection.sink.logging.autoconfigure;

import com.obsinity.collection.core.receivers.EventHandler;
import com.obsinity.collection.sink.logging.LoggingEventSink;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class LoggingSinkAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "obsinity.collection.logging",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(name = "loggingEventSink")
    public EventHandler loggingEventSink() {
        return new LoggingEventSink();
    }
}

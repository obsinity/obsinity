package com.obsinity.collection.receiver.logging.autoconfigure;

import com.obsinity.collection.core.receivers.EventHandler;
import com.obsinity.collection.sink.logging.LoggingEventReceiver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class LoggingReceiverAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "obsinity.collection.logging",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(name = "loggingEventReceiver")
    public EventHandler loggingEventReceiver() {
        return new LoggingEventReceiver();
    }
}

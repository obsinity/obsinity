package com.obsinity.collection.sink.obsinity.autoconfigure;

import com.obsinity.client.transport.EventSender;
import com.obsinity.collection.core.receivers.EventHandler;
import com.obsinity.collection.sink.obsinity.ObsinityEventSink;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnBean(EventSender.class)
@ConditionalOnProperty(
        prefix = "obsinity.collection.obsinity",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ObsinitySinkAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "obsinityEventSink")
    public EventHandler obsinityEventSink(EventSender sender) {
        return new ObsinityEventSink(sender);
    }
}

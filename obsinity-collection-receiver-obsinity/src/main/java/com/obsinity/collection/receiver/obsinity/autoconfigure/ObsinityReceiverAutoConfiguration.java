package com.obsinity.collection.receiver.obsinity.autoconfigure;

import com.obsinity.client.transport.EventSender;
import com.obsinity.collection.core.receivers.EventHandler;
import com.obsinity.collection.core.receivers.TelemetryReceiver;
import com.obsinity.collection.receiver.obsinity.ObsinityEventReceiver;
import com.obsinity.collection.receiver.obsinity.TelemetryObsinityReceiver;
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
public class ObsinityReceiverAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "obsinityEventReceiver")
    public EventHandler obsinityEventReceiver(EventSender sender) {
        return new ObsinityEventReceiver(sender);
    }

    @Bean
    @ConditionalOnMissingBean(name = "telemetryObsinityReceiver")
    public TelemetryReceiver telemetryObsinityReceiver(EventSender sender) {
        return new TelemetryObsinityReceiver(sender);
    }
}

package com.obsinity.collection.receiver.obsinity.autoconfigure;

import com.obsinity.client.transport.EventSender;
import com.obsinity.collection.core.receivers.TelemetryReceiver;
import com.obsinity.collection.receiver.obsinity.TelemetryObsinityReceivers;
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
    @ConditionalOnMissingBean(name = "telemetryObsinityReceivers")
    public TelemetryObsinityReceivers telemetryObsinityReceivers(EventSender sender) {
        return new TelemetryObsinityReceivers(sender);
    }
}

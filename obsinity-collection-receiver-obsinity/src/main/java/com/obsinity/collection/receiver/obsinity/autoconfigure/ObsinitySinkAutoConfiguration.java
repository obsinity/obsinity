package com.obsinity.collection.receiver.obsinity.autoconfigure;

import com.obsinity.client.transport.EventSender;
import com.obsinity.collection.receiver.obsinity.TelemetryObsinitySink;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnBean(EventSender.class)
@ConditionalOnProperty(
        prefix = "obsinity.collection.obsinity",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ObsinitySinkAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "telemetryObsinitySink")
    public TelemetryObsinitySink telemetryObsinitySink(EventSender sender, Environment environment) {
        String configuredService = environment.getProperty("obsinity.collection.service");
        return new TelemetryObsinitySink(sender, configuredService);
    }
}

package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.dispatch.AsyncDispatchBus;
import com.obsinity.collection.core.processor.DefaultTelemetryProcessor;
import com.obsinity.collection.core.processor.TelemetryProcessor;
import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.collection.spring.aspect.TelemetryAspect;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({TelemetryAspect.class, TelemetrySupportAutoConfiguration.class})
public class CollectionAutoConfiguration {

    @Bean
    public AsyncDispatchBus asyncDispatchBus(TelemetryHandlerRegistry registry) {
        return new AsyncDispatchBus(registry);
    }

    @Bean
    public TelemetryProcessor telemetryProcessor(AsyncDispatchBus asyncBus, TelemetryProcessorSupport support) {
        return new DefaultTelemetryProcessor(asyncBus, support);
    }
}

package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.dispatch.AsyncDispatchBus;
import com.obsinity.collection.core.processor.DefaultFlowProcessor;
import com.obsinity.collection.core.processor.FlowProcessor;
import com.obsinity.collection.core.sinks.FlowHandlerRegistry;
import com.obsinity.collection.spring.aspect.FlowAspect;
import com.obsinity.flow.processor.FlowProcessorSupport;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({FlowAspect.class, FlowSupportAutoConfiguration.class})
public class CollectionAutoConfiguration {

    @Bean
    public AsyncDispatchBus asyncDispatchBus(FlowHandlerRegistry registry) {
        return new AsyncDispatchBus(registry);
    }

    @Bean
    public FlowProcessor telemetryProcessor(AsyncDispatchBus asyncBus, FlowProcessorSupport support) {
        return new DefaultFlowProcessor(asyncBus, support);
    }
}

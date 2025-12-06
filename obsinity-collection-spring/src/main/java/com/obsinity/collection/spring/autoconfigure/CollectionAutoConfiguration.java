package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.dispatch.AsyncDispatchBus;
import com.obsinity.collection.core.processor.DefaultFlowProcessor;
import com.obsinity.collection.core.processor.FlowProcessor;
import com.obsinity.collection.core.sinks.FlowHandlerRegistry;
import com.obsinity.collection.spring.aspect.FlowAspect;
import com.obsinity.flow.processor.FlowProcessorSupport;
import com.obsinity.flow.validation.FlowAttributeValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({FlowAspect.class, FlowSupportAutoConfiguration.class})
@ComponentScan(basePackages = "com.obsinity.collection.spring.validation")
public class CollectionAutoConfiguration {

    @Bean
    public AsyncDispatchBus asyncDispatchBus(FlowHandlerRegistry registry) {
        return new AsyncDispatchBus(registry);
    }

    /**
     * Creates the FlowProcessor with optional validator injection.
     * The validator (HibernateEntityDetector or LoggingEntityDetector) is automatically
     * selected based on the hibernate-entity-check configuration property.
     */

    @Bean
    public FlowProcessor telemetryProcessor(
            AsyncDispatchBus asyncBus,
            FlowProcessorSupport support,
            @Autowired(required = false) FlowAttributeValidator validator) {
        return new DefaultFlowProcessor(asyncBus, support, validator);
    }
}



package com.obsinity.collection.core.configuration;

import static org.springframework.core.Ordered.HIGHEST_PRECEDENCE;

import com.obsinity.flow.processor.FlowContext;
import com.obsinity.flow.processor.FlowProcessorSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigureOrder(value = HIGHEST_PRECEDENCE)
@ComponentScan(basePackages = {"com.obsinity"})
@RequiredArgsConstructor
public class AutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public FlowContext flowContext(FlowProcessorSupport flowProcessorSupport) {
        return new FlowContext(flowProcessorSupport);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowProcessorSupport flowProcessorSupport() {
        return new FlowProcessorSupport();
    }
}

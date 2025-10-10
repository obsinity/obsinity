package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.flow.processor.FlowContext;
import com.obsinity.flow.processor.FlowProcessorSupport;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class FlowSupportAutoConfiguration {

    @Bean
    public FlowProcessorSupport flowProcessorSupport() {
        return new FlowProcessorSupport();
    }

    @Bean
    public FlowContext flowContext(FlowProcessorSupport support) {
        return new FlowContext(support);
    }
}

package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.flow.processor.FlowContext;
import com.obsinity.flow.processor.FlowProcessorSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class FlowSupportAutoConfiguration {

    @Bean
    public FlowProcessorSupport flowProcessorSupport(
            @Autowired(required = false) ObsinityCollectionProperties properties) {
        FlowProcessorSupport support = new FlowProcessorSupport();
        // Set enabled flag from properties if available
        if (properties != null) {
            support.setEnabled(properties.isEnabled());
        }
        return support;
    }

    @Bean
    public FlowContext flowContext(FlowProcessorSupport support) {
        return new FlowContext(support);
    }
}

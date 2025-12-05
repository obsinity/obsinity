package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.spring.web.TraceContextFilter;
import com.obsinity.flow.processor.FlowProcessorSupport;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(Filter.class)
public class TraceAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "obsinity.collection.trace",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(name = "obsinityTraceContextFilter")
    public Filter obsinityTraceContextFilter(@Autowired(required = false) FlowProcessorSupport support) {
        return new TraceContextFilter(support);
    }
}

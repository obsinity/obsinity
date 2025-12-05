package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.spring.webflux.TraceContextWebFilter;
import com.obsinity.flow.processor.FlowProcessorSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.server.WebFilter;

@AutoConfiguration
@ConditionalOnClass(WebFilter.class)
public class WebfluxTraceAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "obsinity.collection.trace",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(name = "obsinityWebfluxTraceContextFilter")
    public WebFilter obsinityWebfluxTraceContextFilter(@Autowired(required = false) FlowProcessorSupport support) {
        return new TraceContextWebFilter(support);
    }
}

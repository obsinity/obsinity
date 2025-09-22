package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.spring.web.TraceContextFilter;
import jakarta.servlet.Filter;
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
    public Filter obsinityTraceContextFilter() {
        return new TraceContextFilter();
    }
}

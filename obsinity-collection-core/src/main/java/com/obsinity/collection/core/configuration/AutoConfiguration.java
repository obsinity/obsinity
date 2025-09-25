package com.obsinity.collection.core.configuration;

import static org.springframework.core.Ordered.HIGHEST_PRECEDENCE;

import com.obsinity.telemetry.processor.TelemetryContext;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;
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
    public TelemetryContext telemetryContext(TelemetryProcessorSupport telemetryProcessorSupport) {
        return new TelemetryContext(telemetryProcessorSupport);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelemetryProcessorSupport telemetryProcessorSupport() {
        return new TelemetryProcessorSupport();
    }
}

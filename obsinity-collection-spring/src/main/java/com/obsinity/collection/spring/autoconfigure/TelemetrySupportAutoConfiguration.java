package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.telemetry.processor.TelemetryContext;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class TelemetrySupportAutoConfiguration {

    @Bean
    public TelemetryProcessorSupport telemetryProcessorSupport() {
        return new TelemetryProcessorSupport();
    }

    @Bean
    public TelemetryContext telemetryContext(TelemetryProcessorSupport support) {
        return new TelemetryContext(support);
    }
}

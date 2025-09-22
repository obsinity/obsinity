package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.core.dispatch.DispatchBus;
import com.obsinity.collection.core.processor.DefaultTelemetryProcessor;
import com.obsinity.collection.core.processor.TelemetryProcessor;
import com.obsinity.collection.core.sink.EventSink;
import com.obsinity.collection.spring.aspect.TelemetryAspect;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import(TelemetryAspect.class)
public class CollectionAutoConfiguration {

    @Bean
    public DispatchBus dispatchBus(List<EventSink> sinks) {
        return new DispatchBus(sinks);
    }

    @Bean
    public TelemetryProcessor telemetryProcessor(DispatchBus bus) {
        return new DefaultTelemetryProcessor(bus);
    }
}

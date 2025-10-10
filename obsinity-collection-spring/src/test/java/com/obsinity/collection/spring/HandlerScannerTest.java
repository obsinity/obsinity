package com.obsinity.collection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.core.dispatch.AsyncDispatchBus;
import com.obsinity.collection.core.receivers.FlowSinkHandler;
import com.obsinity.telemetry.model.FlowEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@SpringBootTest(classes = HandlerScannerTest.TestConfig.class)
@ImportAutoConfiguration(
        classes = {
            com.obsinity.collection.spring.autoconfigure.CollectionAutoConfiguration.class,
            com.obsinity.collection.spring.autoconfigure.HandlerAutoConfiguration.class
        })
class HandlerScannerTest {

    @Configuration
    static class TestConfig {
        @Bean
        TestSink testSink() {
            return new TestSink();
        }
    }

    static class TestSink implements FlowSinkHandler {
        static final List<FlowEvent> received = new ArrayList<>();

        @Override
        public void handle(FlowEvent h) {
            received.add(h);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private AsyncDispatchBus bus;

    @Test
    void scanner_registers_sink_handlers() {
        FlowEvent h =
                FlowEvent.builder().name("unit.test").timestamp(Instant.now()).build();
        bus.dispatch(h);
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        assertThat(TestSink.received).hasSize(1);
        assertThat(TestSink.received.get(0).name()).isEqualTo("unit.test");
        TestSink.received.clear();
    }
}

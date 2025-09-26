package com.obsinity.collection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.core.dispatch.AsyncDispatchBus;
import com.obsinity.collection.core.receivers.TelemetryReceiver;
import com.obsinity.telemetry.model.TelemetryHolder;
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
        TestReceiver testReceiver() {
            return new TestReceiver();
        }
    }

    static class TestReceiver implements TelemetryReceiver {
        static final List<TelemetryHolder> received = new ArrayList<>();

        @Override
        public void handle(TelemetryHolder h) {
            received.add(h);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private AsyncDispatchBus bus;

    @Test
    void scanner_registers_receiver_methods() {
        TelemetryHolder h = TelemetryHolder.builder()
                .name("unit.test")
                .timestamp(Instant.now())
                .build();
        bus.dispatch(h);
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        assertThat(TestReceiver.received).hasSize(1);
        assertThat(TestReceiver.received.get(0).name()).isEqualTo("unit.test");
        TestReceiver.received.clear();
    }
}

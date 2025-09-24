package com.obsinity.collection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.core.dispatch.DispatchBus;
import com.obsinity.collection.core.model.OEvent;
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

    static class TestReceiver implements com.obsinity.collection.core.receivers.EventHandler {
        static final List<OEvent> received = new ArrayList<>();

        @Override
        public void handle(OEvent e) {
            received.add(e);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private DispatchBus bus;

    @Test
    void scanner_registers_receiver_methods() {
        OEvent e = OEvent.builder()
                .occurredAt(Instant.now())
                .name("unit.test:started")
                .attributes(java.util.Map.of())
                .build();
        bus.dispatch(e);
        assertThat(TestReceiver.received).hasSize(1);
        assertThat(TestReceiver.received.get(0).name()).isEqualTo("unit.test:started");
        TestReceiver.received.clear();
    }
}

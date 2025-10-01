package com.obsinity.collection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.api.annotations.Domain;
import com.obsinity.collection.api.annotations.Flow;
import com.obsinity.collection.api.annotations.Kind;
import com.obsinity.collection.api.annotations.PushAttribute;
import com.obsinity.collection.api.annotations.PushContextValue;
import com.obsinity.collection.core.receivers.TelemetryReceiver;
import com.obsinity.telemetry.model.TelemetryHolder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = TelemetryAspectBindingTest.TestConfig.class)
@ImportAutoConfiguration(
        classes = {
            com.obsinity.collection.spring.autoconfigure.CollectionAutoConfiguration.class,
            com.obsinity.collection.spring.autoconfigure.HandlerAutoConfiguration.class
        })
@TestPropertySource(
        properties = {"obsinity.collection.logging.enabled=false", "spring.main.allow-bean-definition-overriding=true"})
class TelemetryAspectBindingTest {

    @Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
    static class TestConfig {
        @Bean
        SampleFlows sampleFlows() {
            return new SampleFlows();
        }

        @Bean
        CapturingReceiver capturingReceiver() {
            return new CapturingReceiver();
        }

        static class SampleFlows {
            @Flow(name = "demo.checkout")
            @Kind(io.opentelemetry.api.trace.SpanKind.SERVER)
            @Domain("http")
            public void checkout(@PushAttribute("user.id") String userId, @PushContextValue("cart.size") int items) {
                // no-op
            }
        }

        /** Simple receiver bean that captures TelemetryHolder fan-out. */
        static class CapturingReceiver implements TelemetryReceiver {
            final List<TelemetryHolder> holders = new ArrayList<>();

            @Override
            public void handle(TelemetryHolder holder) {
                holders.add(holder);
            }
        }

        @org.springframework.beans.factory.annotation.Autowired
        private SampleFlows flows;

        @org.springframework.beans.factory.annotation.Autowired
        private CapturingReceiver capturingReceiver;

        @AfterEach
        void clear() {
            capturingReceiver.holders.clear();
        }

        @Test
        void emits_started_and_completed_with_attributes_and_context() {
            flows.checkout("alice", 3);

            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            List<TelemetryHolder> seen = capturingReceiver.holders;
            assertThat(seen).hasSize(2);
            TelemetryHolder started = seen.get(0);
            TelemetryHolder finished = seen.get(1);

            assertThat(started.name()).isEqualTo("demo.checkout");
            assertThat(finished.name()).isEqualTo("demo.checkout");

            assertThat(started.attributes().map()).containsEntry("user.id", "alice");
            assertThat(finished.attributes().map()).containsEntry("user.id", "alice");
            assertThat(started.eventContext()).containsEntry("cart.size", 3);
            assertThat(finished.eventContext()).containsEntry("cart.size", 3);
        }
    }
}

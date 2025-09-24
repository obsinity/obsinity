package com.obsinity.collection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.collection.api.annotations.Domain;
import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.Flow;
import com.obsinity.collection.api.annotations.Kind;
import com.obsinity.collection.api.annotations.PushAttribute;
import com.obsinity.collection.api.annotations.PushContextValue;
import com.obsinity.collection.core.context.TelemetryContext;
import com.obsinity.collection.core.model.OEvent;
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
        RecordingReceiver recordingReceiver() {
            return new RecordingReceiver();
        }

        @Bean
        RecordingSink recordingSink() {
            return new RecordingSink();
        }

        static class SampleFlows {
            @Flow(name = "demo.checkout")
            @Kind("SERVER")
            @Domain("http")
            public void checkout(@PushAttribute("user.id") String userId, @PushContextValue("cart.size") int items) {
                // no-op
            }
        }

        @EventReceiver
        static class RecordingReceiver {
            final List<OEvent> events = new ArrayList<>();

            @com.obsinity.collection.api.annotations.OnFlowStarted
            public void onStart(OEvent e) {
                events.add(e);
            }

            @com.obsinity.collection.api.annotations.OnFlowCompleted
            public void onCompleted(OEvent e) {
                events.add(e);
            }
        }

        /**
         * Simple EventSink that records all dispatched events (bypasses handler scanner).
         */
        static class RecordingSink implements com.obsinity.collection.core.receivers.EventHandler {
            final List<OEvent> events = new ArrayList<>();

            @Override
            public void handle(OEvent event) {
                events.add(event);
            }
        }

        @org.springframework.beans.factory.annotation.Autowired
        private SampleFlows flows;

        @org.springframework.beans.factory.annotation.Autowired
        private RecordingReceiver receiver;

        @org.springframework.beans.factory.annotation.Autowired
        private RecordingSink sink;

        @AfterEach
        void clear() {
            TelemetryContext.clear();
            receiver.events.clear();
        }

        @Test
        void emits_started_and_completed_with_attributes_and_context() {
            TelemetryContext.putContext("cart.size", 3);
            flows.checkout("alice", 3);

            List<OEvent> seen = !receiver.events.isEmpty() ? receiver.events : sink.events;
            assertThat(seen).hasSize(2);
            OEvent started = seen.get(0);
            OEvent finished = seen.get(1);

            assertThat(started.name()).isEqualTo("demo.checkout:started");
            assertThat(finished.name()).isEqualTo("demo.checkout:completed");

            assertThat(started.attributes()).containsEntry("user.id", "alice");
            assertThat(finished.attributes()).containsEntry("user.id", "alice");

            assertThat(started.context()).containsEntry("cart.size", 3);
            assertThat(finished.context()).containsEntry("cart.size", 3);
        }
    }
}

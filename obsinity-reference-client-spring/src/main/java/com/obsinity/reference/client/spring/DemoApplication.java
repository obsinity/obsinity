package com.obsinity.reference.client.spring;

import com.obsinity.collection.api.annotations.Domain;
import com.obsinity.collection.api.annotations.Flow;
import com.obsinity.collection.api.annotations.Kind;
import com.obsinity.collection.api.annotations.PushAttribute;
import com.obsinity.collection.api.annotations.PushContextValue;
import com.obsinity.collection.core.context.TelemetryContext;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    CommandLineRunner demoRunner(SampleFlows flows) {
        return args -> {
            TelemetryContext.putContext("session.id", UUID.randomUUID().toString());
            // Demo trace context (picked up by aspect)
            TelemetryContext.putContext("traceId", "4a3f1b5e2f9d4c1aa0b2c3d4e5f60718");
            TelemetryContext.putContext("spanId", "7b2c3d4e5f607182");
            flows.checkout("alice", 42);
            flows.checkout("bob", 99);
            try {
                flows.checkoutFail("charlie", -1);
            } catch (RuntimeException ignore) {
            }
        };
    }
}

@Component
class SampleFlows {
    @Flow(name = "demo.checkout")
    @Kind("SERVER")
    @Domain("http")
    public void checkout(@PushAttribute("user.id") String userId, @PushContextValue("cart.size") int items) {
        // business logic ...
    }

    @Flow(name = "demo.checkout")
    @Kind("SERVER")
    @Domain("http")
    public void checkoutFail(@PushAttribute("user.id") String userId, @PushContextValue("cart.size") int items) {
        throw new RuntimeException("boom");
    }
}

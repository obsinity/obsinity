package com.obsinity.collection.sink.obsinity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.client.transport.EventSender;
import com.obsinity.collection.receiver.obsinity.TelemetryObsinityReceivers;
import com.obsinity.telemetry.model.OStatus;
import com.obsinity.telemetry.model.TelemetryEvent;
import io.opentelemetry.api.trace.StatusCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ObsinityEventReceiverTest {
    static class CapturingSender implements EventSender {
        byte[] last;

        @Override
        public void send(byte[] body) {
            last = body;
        }
    }

    @Test
    void maps_basic_fields() throws Exception {
        var sender = new CapturingSender();
        var sink = new TelemetryObsinityReceivers(sender);

        TelemetryEvent h = TelemetryEvent.builder()
                .name("demo.checkout")
                .timestamp(Instant.parse("2025-09-24T08:30:00Z"))
                .status(new OStatus(StatusCode.OK, null))
                .serviceId("demo-service")
                .build();
        h.attributes().put("user.id", "alice");
        h.eventContext().put("cart.size", 3);

        sink.onCompleted(h);

        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(sender.last);
        assertThat(root.path("event").path("name").asText()).isEqualTo("demo.checkout");
        assertThat(root.path("status").path("code").asText()).isEqualTo("OK");
        assertThat(root.path("attributes").path("user.id").asText()).isEqualTo("alice");
        assertThat(root.path("resource").path("context").path("cart.size").asInt())
                .isEqualTo(3);
    }
}

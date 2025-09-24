package com.obsinity.collection.sink.obsinity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.client.transport.EventSender;
import com.obsinity.collection.core.model.OEvent;
import org.junit.jupiter.api.Test;

class ObsinityEventSinkTest {
    static class CapturingSender implements EventSender {
        byte[] last;
        @Override public void send(byte[] body) { last = body; }
    }

    @Test
    void maps_basic_fields() throws Exception {
        var sender = new CapturingSender();
        var sink = new ObsinityEventSink(sender);

        OEvent ev = OEvent.builder()
                .occurredAt(java.time.Instant.parse("2025-09-24T08:30:00Z"))
                .name("demo.checkout:completed")
                .attributes(java.util.Map.of("user.id", "alice"))
                .resourceContext(java.util.Map.of("cart.size", 3))
                .build();

        sink.handle(ev);

        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(sender.last);
        assertThat(root.path("event").path("name").asText()).isEqualTo("demo.checkout");
        assertThat(root.path("status").path("code").asText()).isEqualTo("COMPLETED");
        assertThat(root.path("attributes").path("user.id").asText()).isEqualTo("alice");
        assertThat(root.path("resource").path("context").path("cart.size").asInt()).isEqualTo(3);
    }
}

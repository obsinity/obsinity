package com.obsinity.client.transport.webclient;

import com.obsinity.client.transport.EventSender;
import java.io.IOException;
import org.springframework.web.reactive.function.client.WebClient;

/** Spring WebClient sender (blocking send for simplicity). */
public class WebClientEventSender implements EventSender {
    private final WebClient client = WebClient.builder().build();

    @Override
    public void send(byte[] body) throws IOException {
        Integer code = client.post()
                .uri(endpoint())
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchangeToMono(r -> r.toBodilessEntity().map(e -> r.statusCode().value()))
                .block();
        if (code == null || code >= 400) throw new IOException("HTTP " + code);
    }
}

package com.obsinity.client.transport.resttemplate;

import com.obsinity.client.transport.EventSender;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

/** Spring RestTemplate sender. */
public class RestTemplateEventSender implements EventSender {
    private final RestTemplate rt = new RestTemplate();

    @Override
    public void send(byte[] body) throws IOException {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> resp = rt.exchange(endpoint(), HttpMethod.POST, new HttpEntity<>(body, h), Void.class);
        if (!resp.getStatusCode().is2xxSuccessful()) throw new IOException("HTTP " + resp.getStatusCodeValue());
    }
}

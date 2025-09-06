package com.obsinity.client.transport.jdkhttp;

import com.obsinity.client.transport.EventSender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** JDK11+ HttpClient sender (zero external deps). */
public class JdkHttpEventSender implements EventSender {
    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public void send(byte[] body) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) throw new IOException("HTTP " + resp.statusCode());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", ie);
        }
    }
}

package com.obsinity.client.transport.okhttp;

import com.obsinity.client.transport.EventSender;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OkHttp-based sender. */
public class OkHttpEventSender implements EventSender {
    private static final Logger log = LoggerFactory.getLogger(OkHttpEventSender.class);
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Dns PREFER_IPV4_DNS = hostname -> {
        var addresses = new ArrayList<>(Dns.SYSTEM.lookup(hostname));
        addresses.sort((a, b) -> {
            boolean aV4 = a instanceof Inet4Address;
            boolean bV4 = b instanceof Inet4Address;
            if (aV4 == bV4) return 0;
            return aV4 ? -1 : 1;
        });
        return addresses;
    };
    private final OkHttpClient client =
            new OkHttpClient.Builder().dns(PREFER_IPV4_DNS).build();

    @Override
    public void send(byte[] body) throws IOException {
        String endpoint = endpoint();
        String payload = new String(body, StandardCharsets.UTF_8);
        Request req = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(body, JSON))
                .build();
        log.info("Sending telemetry request {} {} with body: {}", req.method(), endpoint, payload);
        try (Response r = client.newCall(req).execute()) {
            String responseBody = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) {
                log.warn(
                        "Telemetry request {} {} failed with status {} and body: {}",
                        req.method(),
                        endpoint,
                        r.code(),
                        responseBody);
                throw new IOException("HTTP " + r.code() + " - " + responseBody);
            }
            if (log.isDebugEnabled()) {
                log.debug(
                        "Telemetry request {} {} succeeded with status {} and body: {}",
                        req.method(),
                        endpoint,
                        r.code(),
                        responseBody);
            }
        }
    }

    @Override
    public void close() {
        client.connectionPool().evictAll();
    }
}

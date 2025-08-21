package com.obsinity.client.transport.okhttp;

import com.obsinity.client.transport.EventSender;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/** OkHttp-based sender. */
public class OkHttpEventSender implements EventSender {
  private static final MediaType JSON = MediaType.parse("application/json");
  private final OkHttpClient client = new OkHttpClient();

  @Override public void send(byte[] body) throws IOException {
    Request req = new Request.Builder()
        .url(endpoint())
        .post(RequestBody.create(body, JSON))
        .build();
    try (Response r = client.newCall(req).execute()) {
      // ignore body; throw on HTTP failure
      if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
    }
  }

  @Override public void close() { client.connectionPool().evictAll(); }
}

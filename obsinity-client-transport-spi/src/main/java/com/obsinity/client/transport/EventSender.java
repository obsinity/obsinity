package com.obsinity.client.transport;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

/**
 * Minimal transport SPI: send serialized payloads to Obsinity ingest.
 */
public interface EventSender extends Closeable {
  String DEFAULT_ENDPOINT = "http://localhost:4318/obsinity/ingest";
  String PROP_ENDPOINT = "obsinity.ingest.url";
  String ENV_ENDPOINT  = "OBSINITY_INGEST_URL";

  void send(byte[] body) throws IOException;

  default String endpoint() {
    String sys = System.getProperty(PROP_ENDPOINT);
    if (sys != null && !sys.isBlank()) return sys;
    String env = System.getenv(ENV_ENDPOINT);
    if (env != null && !env.isBlank()) return env;
    return DEFAULT_ENDPOINT;
  }

  @Override default void close() throws IOException { /* no-op */ }

  static byte[] requireBytes(String s) {
    return Objects.requireNonNull(s, "payload").getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}

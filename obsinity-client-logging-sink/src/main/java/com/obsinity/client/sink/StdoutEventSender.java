package com.obsinity.client.sink;

import com.obsinity.client.transport.EventSender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Dev-only sink that prints payloads to stdout. */
public class StdoutEventSender implements EventSender {
  @Override public void send(byte[] body) throws IOException {
    System.out.println("[Obsinity] " + new String(body, StandardCharsets.UTF_8));
  }
}

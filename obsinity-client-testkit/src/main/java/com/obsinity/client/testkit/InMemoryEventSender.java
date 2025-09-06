package com.obsinity.client.testkit;

import com.obsinity.client.transport.EventSender;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test double that records payloads in memory. */
public class InMemoryEventSender implements EventSender {
    private final List<byte[]> payloads = new ArrayList<>();

    @Override
    public void send(byte[] body) throws IOException {
        payloads.add(body);
    }

    public List<byte[]> payloads() {
        return Collections.unmodifiableList(payloads);
    }

    public void clear() {
        payloads.clear();
    }
}

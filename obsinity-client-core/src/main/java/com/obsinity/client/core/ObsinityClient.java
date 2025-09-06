package com.obsinity.client.core;

import com.obsinity.client.transport.EventSender;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Tiny client that serializes a minimal JSON event and sends it via the chosen EventSender.
 * Real implementation would use Jackson; we inline a tiny JSON to keep the bootstrap simple.
 */
public final class ObsinityClient implements AutoCloseable {
    private final EventSender sender;

    public ObsinityClient(EventSender sender) {
        this.sender = sender;
    }

    public void recordFlow(String name) throws IOException {
        Map<String, Object> attrs = TelemetryContext.snapshotAttrs();
        Map<String, Object> ctx = TelemetryContext.snapshotContext();
        String json = "{\"ts\":\"" + Instant.now() + "\",\"type\":\"flow\",\"name\":\"" + esc(name)
                + "\",\"attributes\":" + mapToJson(attrs) + ",\"context\":" + mapToJson(ctx) + "}";
        sender.send(EventSender.requireBytes(json));
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String mapToJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"").append(esc(String.valueOf(e.getKey()))).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v.toString());
            else sb.append("\"").append(esc(String.valueOf(v))).append("\"");
        }
        return sb.append("}").toString();
    }

    @Override
    public void close() throws IOException {
        sender.close();
    }
}

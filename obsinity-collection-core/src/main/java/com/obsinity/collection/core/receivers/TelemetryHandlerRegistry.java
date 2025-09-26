package com.obsinity.collection.core.receivers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TelemetryHandlerRegistry {
    private final CopyOnWriteArrayList<TelemetryReceiver> handlers = new CopyOnWriteArrayList<>();

    public void register(TelemetryReceiver h) {
        if (h != null) handlers.add(h);
    }

    public List<TelemetryReceiver> handlers() {
        return List.copyOf(handlers);
    }
}

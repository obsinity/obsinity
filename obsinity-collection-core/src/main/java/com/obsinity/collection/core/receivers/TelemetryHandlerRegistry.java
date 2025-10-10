package com.obsinity.collection.core.receivers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TelemetryHandlerRegistry {
    private final CopyOnWriteArrayList<FlowSinkHandler> handlers = new CopyOnWriteArrayList<>();

    public void register(FlowSinkHandler h) {
        if (h != null) handlers.add(h);
    }

    public List<FlowSinkHandler> handlers() {
        return List.copyOf(handlers);
    }
}

package com.obsinity.collection.core.sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FlowHandlerRegistry {
    private final CopyOnWriteArrayList<FlowSinkHandler> handlers = new CopyOnWriteArrayList<>();

    public void register(FlowSinkHandler h) {
        if (h != null) handlers.add(h);
    }

    public List<FlowSinkHandler> handlers() {
        return List.copyOf(handlers);
    }
}

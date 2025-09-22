package com.obsinity.collection.core.receivers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class HandlerRegistry {
    private final CopyOnWriteArrayList<EventHandler> handlers = new CopyOnWriteArrayList<>();

    public void register(EventHandler h) {
        if (h != null) handlers.add(h);
    }

    public List<EventHandler> handlers() {
        return List.copyOf(handlers);
    }
}

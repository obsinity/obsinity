package com.obsinity.service.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Simple registry placeholder for handler instances. */
public class HandlerRegistry {
    private final List<Object> handlers = new CopyOnWriteArrayList<>();

    public void register(Object handler) {
        handlers.add(handler);
    }

    public List<Object> all() {
        return List.copyOf(handlers);
    }
}

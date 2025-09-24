package com.obsinity.collection.core.dispatch;

import com.obsinity.collection.core.model.OEvent;
import com.obsinity.collection.core.receivers.EventHandler;
import com.obsinity.collection.core.receivers.HandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DispatchBus implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DispatchBus.class);

    private final HandlerRegistry registry;

    public DispatchBus(HandlerRegistry registry) {
        this.registry = registry;
    }

    public void dispatch(OEvent event) {
        for (EventHandler h : registry.handlers()) {
            try {
                h.handle(event);
            } catch (Exception ex) {
                log.warn("Event receiver failure: {}", ex.toString());
            }
        }
    }

    @Override
    public void close() {
        // no-op
    }
}

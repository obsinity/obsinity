package com.obsinity.collection.core.receivers;

import com.obsinity.collection.core.model.OEvent;
import com.obsinity.collection.core.sink.EventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HandlerSink implements EventSink {
    private static final Logger log = LoggerFactory.getLogger(HandlerSink.class);

    private final HandlerRegistry registry;

    public HandlerSink(HandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void accept(OEvent event) {
        for (EventHandler h : registry.handlers()) {
            try {
                h.handle(event);
            } catch (Exception ex) {
                log.warn("Handler failure: {}", ex.toString());
            }
        }
    }
}

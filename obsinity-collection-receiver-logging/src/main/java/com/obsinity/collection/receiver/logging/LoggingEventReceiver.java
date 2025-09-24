package com.obsinity.collection.receiver.logging;

import com.obsinity.collection.core.model.OEvent;
import com.obsinity.collection.core.receivers.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingEventReceiver implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(LoggingEventReceiver.class);

    @Override
    public void handle(OEvent event) {
        if (event == null) return;
        log.info(
                "event name={}, ts={}, attrs={}, ctx={}",
                event.name(),
                event.occurredAt(),
                event.attributes(),
                event.context());
    }
}

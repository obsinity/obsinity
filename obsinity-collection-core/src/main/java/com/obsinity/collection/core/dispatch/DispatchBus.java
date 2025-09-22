package com.obsinity.collection.core.dispatch;

import com.obsinity.collection.core.model.OEvent;
import com.obsinity.collection.core.sink.EventSink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DispatchBus implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DispatchBus.class);

    private final List<EventSink> sinks;

    public DispatchBus(List<EventSink> sinks) {
        this.sinks = (sinks == null) ? List.of() : Collections.unmodifiableList(new ArrayList<>(sinks));
    }

    public void dispatch(OEvent event) {
        for (EventSink s : sinks) {
            try {
                s.accept(event);
            } catch (Exception ex) {
                log.warn("Event sink failure: {}", ex.toString());
            }
        }
    }

    @Override
    public void close() throws Exception {
        for (EventSink s : sinks) {
            try {
                s.close();
            } catch (Exception ignore) {
                /* noop */
            }
        }
    }
}

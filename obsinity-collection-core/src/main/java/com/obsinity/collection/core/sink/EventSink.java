package com.obsinity.collection.core.sink;

import com.obsinity.collection.core.model.OEvent;
import java.util.Collection;

public interface EventSink extends AutoCloseable {
    void accept(OEvent event) throws Exception;

    default void acceptAll(Collection<OEvent> batch) throws Exception {
        if (batch == null || batch.isEmpty()) return;
        for (OEvent e : batch) accept(e);
    }

    @Override
    default void close() throws Exception {
        /* no-op */
    }
}

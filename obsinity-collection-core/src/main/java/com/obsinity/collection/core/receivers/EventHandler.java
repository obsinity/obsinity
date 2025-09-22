package com.obsinity.collection.core.receivers;

import com.obsinity.collection.core.model.OEvent;

@FunctionalInterface
public interface EventHandler {
    void handle(OEvent event) throws Exception;
}

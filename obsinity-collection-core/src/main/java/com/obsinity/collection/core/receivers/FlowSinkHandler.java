package com.obsinity.collection.core.receivers;

import com.obsinity.telemetry.model.FlowEvent;

@FunctionalInterface
public interface FlowSinkHandler {
    void handle(FlowEvent holder) throws Exception;
}

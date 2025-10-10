package com.obsinity.collection.core.sinks;

import com.obsinity.flow.model.FlowEvent;

@FunctionalInterface
public interface FlowSinkHandler {
    void handle(FlowEvent holder) throws Exception;
}

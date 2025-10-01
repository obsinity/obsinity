package com.obsinity.collection.core.receivers;

import com.obsinity.telemetry.model.TelemetryEvent;

@FunctionalInterface
public interface TelemetryReceiver {
    void handle(TelemetryEvent holder) throws Exception;
}

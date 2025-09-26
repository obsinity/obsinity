package com.obsinity.collection.core.receivers;

import com.obsinity.telemetry.model.TelemetryHolder;

@FunctionalInterface
public interface TelemetryReceiver {
    void handle(TelemetryHolder holder) throws Exception;
}

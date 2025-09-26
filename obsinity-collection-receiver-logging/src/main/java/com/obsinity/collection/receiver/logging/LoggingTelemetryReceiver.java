package com.obsinity.collection.receiver.logging;

import com.obsinity.collection.core.receivers.TelemetryReceiver;
import com.obsinity.telemetry.model.TelemetryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingTelemetryReceiver implements TelemetryReceiver {
    private static final Logger log = LoggerFactory.getLogger(LoggingTelemetryReceiver.class);

    @Override
    public void handle(TelemetryHolder holder) {
        if (holder == null) return;
        log.info(
                "holder name={}, ts={}, attrs={}, ctx={}",
                holder.name(),
                holder.timestamp(),
                holder.attributes().map(),
                holder.eventContext());
    }
}


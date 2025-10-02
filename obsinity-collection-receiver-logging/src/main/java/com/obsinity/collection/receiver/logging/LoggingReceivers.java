package com.obsinity.collection.receiver.logging;

import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.telemetry.model.TelemetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventReceiver
public class LoggingReceivers {
    private static final Logger log = LoggerFactory.getLogger(LoggingReceivers.class);

    @OnFlowStarted
    public void onStart(TelemetryEvent event) {
        log.info("START {} attrs={} ctx={}", event.name(), event.attributes().map(), event.eventContext());
    }

    @OnFlowCompleted
    public void onCompleted(TelemetryEvent event) {
        log.info("DONE  {} attrs={} ctx={}", event.name(), event.attributes().map(), event.eventContext());
    }

    @OnFlowFailure
    public void onFailed(TelemetryEvent event) {
        log.warn("FAIL  {} attrs={} ctx={}", event.name(), event.attributes().map(), event.eventContext());
    }
}

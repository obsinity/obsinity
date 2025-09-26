package com.obsinity.collection.receiver.logging;

import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.telemetry.model.TelemetryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventReceiver
public class LoggingReceivers {
    private static final Logger log = LoggerFactory.getLogger(LoggingReceivers.class);

    @OnFlowStarted
    public void onStart(TelemetryHolder h) {
        log.info("START {} attrs={} ctx={}", h.name(), h.attributes().map(), h.eventContext());
    }

    @OnFlowCompleted
    public void onCompleted(TelemetryHolder h) {
        log.info("DONE  {} attrs={} ctx={}", h.name(), h.attributes().map(), h.eventContext());
    }

    @OnFlowFailure
    public void onFailed(TelemetryHolder h) {
        log.warn("FAIL  {} attrs={} ctx={}", h.name(), h.attributes().map(), h.eventContext());
    }
}


package com.obsinity.collection.receiver.logging;

import com.obsinity.collection.api.annotations.FlowSink;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.telemetry.model.FlowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FlowSink
public class LoggingSink {
    private static final Logger log = LoggerFactory.getLogger(LoggingSink.class);

    @OnFlowStarted
    public void onStart(FlowEvent event) {
        log.info("START {} attrs={} ctx={}", event.name(), event.attributes().map(), event.eventContext());
    }

    @OnFlowCompleted
    public void onCompleted(FlowEvent event) {
        log.info("DONE  {} attrs={} ctx={}", event.name(), event.attributes().map(), event.eventContext());
    }

    @OnFlowFailure
    public void onFailed(FlowEvent event) {
        log.warn("FAIL  {} attrs={} ctx={}", event.name(), event.attributes().map(), event.eventContext());
    }
}

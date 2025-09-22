package com.obsinity.reference.client.spring;

import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.collection.core.model.OEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventReceiver
public class SampleReceivers {
    private static final Logger log = LoggerFactory.getLogger(SampleReceivers.class);

    @OnFlowStarted
    public void onStart(OEvent e) {
        log.info("START {} attrs={} ctx={}", e.name(), e.attributes(), e.context());
    }

    @OnFlowCompleted
    public void onCompleted(OEvent e) {
        log.info("DONE  {} attrs={} ctx={}", e.name(), e.attributes(), e.context());
    }

    @OnFlowFailure
    public void onFailed(OEvent e) {
        log.warn("FAIL  {} attrs={} ctx={}", e.name(), e.attributes(), e.context());
    }
}

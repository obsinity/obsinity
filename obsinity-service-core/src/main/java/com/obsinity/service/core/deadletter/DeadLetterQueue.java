package com.obsinity.service.core.deadletter;

import com.obsinity.service.core.model.EventEnvelope;

/** Sink for events that fail validation so they can be retried or inspected later. */
public interface DeadLetterQueue {
    void publish(EventEnvelope envelope, String reason, String detail);
}

package com.obsinity.service.core.unconfigured;

import com.obsinity.service.core.model.EventEnvelope;

/**
 * Sink for events that cannot be processed because the service or event type is not configured.
 */
public interface UnconfiguredEventQueue {
    void publish(EventEnvelope envelope, String reason, String detail);
}

package com.obsinity.service.core.spi;

import com.obsinity.service.core.model.EventEnvelope;

import java.util.List;

public interface EventIngestService {
    /**
     * @return number of records actually stored (idempotent on eventId).
     */
    int ingestOne(EventEnvelope e);

    /**
     * @return number of records stored.
     */
    int ingestBatch(List<EventEnvelope> events);
}

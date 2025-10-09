package com.obsinity.service.core.deadletter;

/**
 * Persists raw payloads that fail basic parsing before they reach the ingest pipeline.
 */
public interface IngestDeadLetterTable {
    void record(String payload, String reason, String detail, String source);
}

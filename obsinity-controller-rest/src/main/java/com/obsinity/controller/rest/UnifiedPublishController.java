package com.obsinity.controller.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.deadletter.IngestDeadLetterTable;
import com.obsinity.service.core.ingest.EventEnvelopeMapper;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Body-routed publish endpoints. Service and event type are provided in the body:
 *   - resource.service.name -> serviceId
 *   - event.name            -> eventType
 */
@RestController
@RequestMapping("/events")
public class UnifiedPublishController {

    private static final Logger log = LoggerFactory.getLogger(UnifiedPublishController.class);
    private static final String SOURCE_PUBLISH_ONE = "REST_PUBLISH_ONE";
    private static final String SOURCE_PUBLISH_BATCH = "REST_PUBLISH_BATCH";
    private final EventIngestService ingest;
    private final ObjectMapper mapper;
    private final IngestDeadLetterTable ingestDeadLetters;
    private final EventEnvelopeMapper envelopeMapper;

    public UnifiedPublishController(
            EventIngestService ingest,
            ObjectMapper mapper,
            IngestDeadLetterTable ingestDeadLetters,
            EventEnvelopeMapper envelopeMapper) {
        this.ingest = ingest;
        this.mapper = mapper;
        this.ingestDeadLetters = ingestDeadLetters;
        this.envelopeMapper = envelopeMapper;
    }

    @PostMapping("/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> publishOne(@RequestBody String body) {
        JsonNode payload = parseBody(body, SOURCE_PUBLISH_ONE);
        try {
            EventEnvelope env = envelopeMapper.fromJson(payload);
            int stored = ingest.ingestOne(env);
            return Map.of("status", stored == 1 ? "stored" : "duplicate", "eventId", env.getEventId());
        } catch (RuntimeException ex) {
            logRejectedPayload(payload, ex);
            throw ex;
        }
    }

    @PostMapping("/publish/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> publishBatch(@RequestBody String body) {
        JsonNode payload = parseBody(body, SOURCE_PUBLISH_BATCH);
        if (!payload.isArray()) {
            IllegalArgumentException invalidPayload =
                    new IllegalArgumentException("Batch payload must be a JSON array");
            recordDeadLetter(body, "INVALID_BATCH_PAYLOAD", invalidPayload, SOURCE_PUBLISH_BATCH);
            logRejectedPayload(body, invalidPayload);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch publish expects a JSON array");
        }

        try {
            List<EventEnvelope> envs = new ArrayList<>(payload.size());
            for (JsonNode node : payload) {
                envs.add(envelopeMapper.fromJson(node));
            }
            int stored = ingest.ingestBatch(envs);
            int duplicates = Math.max(0, envs.size() - stored);
            return Map.of("stored", stored, "duplicates", duplicates);
        } catch (RuntimeException ex) {
            logRejectedPayload(payload, ex);
            throw ex;
        }
    }

    private JsonNode parseBody(String raw, String source) {
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            recordDeadLetter(raw, "JSON_PARSE_ERROR", ex, source);
            logRejectedPayload(raw, ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON payload", ex);
        }
    }

    private void recordDeadLetter(String payload, String reason, Throwable cause, String source) {
        String detail = (cause == null
                        || cause.getMessage() == null
                        || cause.getMessage().isBlank())
                ? null
                : cause.getMessage();
        ingestDeadLetters.record(payload, reason, detail, source);
    }

    private void logRejectedPayload(Object payload, Throwable cause) {
        if (!log.isInfoEnabled()) return;
        try {
            String rendered;
            if (payload instanceof String s) {
                rendered = s;
            } else {
                rendered = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            }
            String reason = (cause == null
                            || cause.getMessage() == null
                            || cause.getMessage().isBlank())
                    ? (cause == null ? "unknown" : cause.getClass().getSimpleName())
                    : cause.getMessage();
            log.info("Rejected publish payload due to {}:\n{}", reason, rendered);
        } catch (JsonProcessingException e) {
            log.info(
                    "Rejected publish payload; failed to render JSON ({}: {})",
                    e.getClass().getSimpleName(),
                    e.getOriginalMessage());
        }
    }
}

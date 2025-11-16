package com.obsinity.ingest.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.deadletter.IngestDeadLetterTable;
import com.obsinity.service.core.ingest.EventEnvelopeMapper;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "obsinity.ingest.kafka", name = "enabled", havingValue = "true")
public class KafkaIngestListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaIngestListener.class);
    private static final String SOURCE = "KAFKA_CONSUMER";

    private final ObjectMapper mapper;
    private final EventEnvelopeMapper envelopeMapper;
    private final EventIngestService ingestService;
    private final IngestDeadLetterTable deadLetterTable;

    public KafkaIngestListener(
            ObjectMapper mapper,
            EventEnvelopeMapper envelopeMapper,
            EventIngestService ingestService,
            IngestDeadLetterTable deadLetterTable) {
        this.mapper = mapper;
        this.envelopeMapper = envelopeMapper;
        this.ingestService = ingestService;
        this.deadLetterTable = deadLetterTable;
    }

    @KafkaListener(
            topics = "${obsinity.ingest.kafka.topic:obsinity.events}",
            containerFactory = "obsinityKafkaListenerFactory")
    public void handle(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        byte[] body = record.value();
        try {
            JsonNode payload = mapper.readTree(body);
            EventEnvelope envelope = envelopeMapper.fromJson(payload);
            ingestService.ingestOne(envelope);
        } catch (Exception ex) {
            recordDeadLetter(body, ex);
            log.warn("Kafka payload persisted to UEQ due to {}. Offset advanced.", ex.getMessage());
        } finally {
            ack.acknowledge();
        }
    }

    private void recordDeadLetter(byte[] payload, Exception ex) {
        try {
            String raw = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            String detail = (ex.getMessage() == null || ex.getMessage().isBlank())
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();
            deadLetterTable.record(raw, "KAFKA_INGEST_ERROR", detail, SOURCE);
        } catch (Exception loggingError) {
            log.error("Failed to write dead letter entry for Kafka payload", loggingError);
        }
    }
}

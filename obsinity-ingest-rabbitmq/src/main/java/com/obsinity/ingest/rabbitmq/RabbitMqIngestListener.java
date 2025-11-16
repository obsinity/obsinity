package com.obsinity.ingest.rabbitmq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.deadletter.IngestDeadLetterTable;
import com.obsinity.service.core.ingest.EventEnvelopeMapper;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(prefix = "obsinity.ingest.rmq", name = "enabled", havingValue = "true")
public class RabbitMqIngestListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqIngestListener.class);
    private static final String SOURCE = "RMQ_CONSUMER";

    private final ObjectMapper mapper;
    private final EventEnvelopeMapper envelopeMapper;
    private final EventIngestService ingestService;
    private final IngestDeadLetterTable deadLetterTable;

    public RabbitMqIngestListener(
            ObjectMapper mapper,
            EventEnvelopeMapper envelopeMapper,
            EventIngestService ingestService,
            IngestDeadLetterTable deadLetterTable) {
        this.mapper = mapper;
        this.envelopeMapper = envelopeMapper;
        this.ingestService = ingestService;
        this.deadLetterTable = deadLetterTable;
    }

    @RabbitListener(queues = "${obsinity.ingest.rmq.queue:obsinity.events}", ackMode = "MANUAL")
    public void handle(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        byte[] body = message.getBody();
        try {
            JsonNode payload = mapper.readTree(body);
            EventEnvelope envelope = envelopeMapper.fromJson(payload);
            ingestService.ingestOne(envelope);
        } catch (Exception ex) {
            recordDeadLetter(body, ex);
            log.warn("RabbitMQ payload persisted to UEQ due to {}. Offset advanced.", ex.getMessage());
        } finally {
            channel.basicAck(tag, false);
        }
    }

    private void recordDeadLetter(byte[] payload, Exception ex) {
        try {
            String raw = new String(payload, StandardCharsets.UTF_8);
            String detail = (ex.getMessage() == null || ex.getMessage().isBlank()) ? ex.getClass().getSimpleName() : ex.getMessage();
            deadLetterTable.record(raw, "RMQ_INGEST_ERROR", detail, SOURCE);
        } catch (Exception loggingError) {
            log.error("Failed to write dead letter entry for RMQ payload", loggingError);
        }
    }
}

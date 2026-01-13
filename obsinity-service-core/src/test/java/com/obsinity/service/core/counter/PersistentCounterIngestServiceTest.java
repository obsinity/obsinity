package com.obsinity.service.core.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.config.EventTypeConfig;
import com.obsinity.service.core.config.PersistentCounterConfig;
import com.obsinity.service.core.config.PersistentCounterOperation;
import com.obsinity.service.core.model.EventEnvelope;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PersistentCounterIngestServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void processBuildsUpdatesAndCallsRepository() throws Exception {
        CounterHashService hashService = new CounterHashService();
        setField(hashService, "cacheSize", 100);
        setField(hashService, "ttl", Duration.ofMinutes(5));
        hashService.init();

        PersistentCounterRepository repository = Mockito.mock(PersistentCounterRepository.class);
        Mockito.when(repository.applyEvent(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(true);

        PersistentCounterIngestService service = new PersistentCounterIngestService(repository, hashService);

        PersistentCounterConfig counterConfig = new PersistentCounterConfig(
                UUID.randomUUID(),
                "connections_total",
                List.of("accountId"),
                PersistentCounterOperation.INCREMENT,
                false,
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());

        EventTypeConfig eventConfig = new EventTypeConfig(
                UUID.randomUUID(),
                "account.connected",
                "account.connected",
                null,
                null,
                Instant.now(),
                List.of(),
                List.of(),
                List.of(counterConfig),
                List.of());

        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId("test")
                .eventType("account.connected")
                .eventId(eventId.toString())
                .timestamp(Instant.now())
                .ingestedAt(Instant.now())
                .attributes(Map.of("accountId", "a-1"))
                .resourceAttributes(Map.of())
                .build();

        service.process(envelope, eventConfig);

        ArgumentCaptor<List<PersistentCounterRepository.CounterUpdate>> updatesCaptor =
                ArgumentCaptor.forClass(List.class);
        Mockito.verify(repository)
                .applyEvent(
                        Mockito.eq(eventId), Mockito.eq("account.connected"), Mockito.any(), updatesCaptor.capture());

        List<PersistentCounterRepository.CounterUpdate> updates = updatesCaptor.getValue();
        assertThat(updates).hasSize(1);
        PersistentCounterRepository.CounterUpdate update = updates.get(0);
        assertThat(update.counterName()).isEqualTo("connections_total");
        assertThat(update.delta()).isEqualTo(1L);
        assertThat(update.floorAtZero()).isFalse();
        assertThat(update.dimensionKey()).isNotBlank();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

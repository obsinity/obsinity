package com.obsinity.service.core.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.config.ConfigLookup;
import com.obsinity.service.core.config.CounterConfig;
import com.obsinity.service.core.config.EventTypeConfig;
import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class CounterPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void bufferFlushAndQueryAcrossGranularities() throws Exception {
        CounterHashService hashService = new CounterHashService();
        setField(hashService, "cacheSize", 1000);
        setField(hashService, "ttl", Duration.ofMinutes(10));
        hashService.init();

        CounterBuffer buffer = new CounterBuffer(hashService);
        InMemoryPersistService persistService = new InMemoryPersistService();
        CounterPersistExecutor executor = new CounterPersistExecutor(persistService, buffer);
        CounterFlushService flushService = new CounterFlushService(buffer, executor);
        setField(flushService, "maxBatchSize", 1000);

        CounterIngestService ingestService = new CounterIngestService(buffer, hashService);

        String serviceKey = "payments";
        String eventType = "transaction.completed";
        UUID serviceId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();

        CounterConfig s5Counter = new CounterConfig(
                UUID.randomUUID(),
                "requests_s5",
                CounterGranularity.S5,
                List.of("http.method"),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        CounterConfig m1Counter = new CounterConfig(
                UUID.randomUUID(),
                "requests_m1",
                CounterGranularity.M1,
                List.of("http.method"),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());
        CounterConfig m5Counter = new CounterConfig(
                UUID.randomUUID(),
                "requests_m5",
                CounterGranularity.M5,
                List.of("http.method"),
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode());

        EventTypeConfig eventConfig = new EventTypeConfig(
                eventTypeId,
                eventType,
                eventType,
                null,
                null,
                Instant.now(),
                List.of(),
                List.of(s5Counter, m1Counter, m5Counter),
                List.of());

        Instant occurredAt = Instant.parse("2025-01-01T00:00:02Z");
        EventEnvelope envelope = EventEnvelope.builder()
                .serviceId(serviceKey)
                .eventType(eventType)
                .eventId(UUID.randomUUID().toString())
                .timestamp(occurredAt)
                .ingestedAt(occurredAt.plusSeconds(1))
                .attributes(Map.of("http.method", "GET"))
                .resourceAttributes(Map.of())
                .build();

        ingestService.process(envelope, eventConfig);

        flushService.flushAndWait(CounterGranularity.S5);
        flushService.flushAndWait(CounterGranularity.M1);
        flushService.flushAndWait(CounterGranularity.M5);

        ConfigLookup configLookup = Mockito.mock(ConfigLookup.class);
        Mockito.when(configLookup.get(serviceId, eventType)).thenReturn(Optional.of(eventConfig));

        ServicesCatalogRepository servicesRepo = Mockito.mock(ServicesCatalogRepository.class);
        Mockito.when(servicesRepo.findIdByServiceKey(serviceKey)).thenReturn(serviceId);

        CounterQueryRepository repository = new InMemoryQueryRepository(persistService.snapshot());
        CounterQueryService queryService = new CounterQueryService(configLookup, servicesRepo, repository, hashService);

        Instant s5Start = CounterBucket.S5.align(occurredAt);
        CounterQueryRequest s5Request = new CounterQueryRequest(
                serviceKey,
                eventType,
                s5Counter.name(),
                Map.of("http.method", List.of("GET")),
                "5s",
                s5Start.toString(),
                s5Start.plusSeconds(5).toString(),
                new CounterQueryRequest.Limits(0, 10));

        CounterQueryResult s5Result = queryService.runQuery(s5Request);
        assertEquals(1, s5Result.windows().size());
        assertEquals(1, s5Result.windows().get(0).counts().get(0).count());

        Instant m1Start = CounterBucket.M1.align(occurredAt);
        CounterQueryRequest m1Request = new CounterQueryRequest(
                serviceKey,
                eventType,
                m1Counter.name(),
                Map.of("http.method", List.of("GET")),
                "1m",
                m1Start.toString(),
                m1Start.plus(Duration.ofMinutes(1)).toString(),
                new CounterQueryRequest.Limits(0, 10));

        CounterQueryResult m1Result = queryService.runQuery(m1Request);
        assertEquals(1, m1Result.windows().size());
        assertEquals(1, m1Result.windows().get(0).counts().get(0).count());

        Instant m5Start = CounterBucket.M5.align(occurredAt);
        CounterQueryRequest m5Request = new CounterQueryRequest(
                serviceKey,
                eventType,
                m5Counter.name(),
                Map.of("http.method", List.of("GET")),
                "5m",
                m5Start.toString(),
                m5Start.plus(Duration.ofMinutes(5)).toString(),
                new CounterQueryRequest.Limits(0, 10));

        CounterQueryResult m5Result = queryService.runQuery(m5Request);
        assertEquals(1, m5Result.windows().size());
        assertEquals(1, m5Result.windows().get(0).counts().get(0).count());

        CounterQueryRequest invalid = new CounterQueryRequest(
                serviceKey,
                eventType,
                m1Counter.name(),
                Map.of("region", List.of("us-east")),
                "5s",
                m1Start.toString(),
                m1Start.plus(Duration.ofMinutes(1)).toString(),
                null);
        assertThrows(IllegalArgumentException.class, () -> queryService.runQuery(invalid));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class InMemoryPersistService extends CounterPersistService {
        private final Map<CounterBucket, Map<Instant, Map<UUID, Map<String, Long>>>> store =
                new EnumMap<>(CounterBucket.class);

        InMemoryPersistService() {
            super(Mockito.mock(JdbcTemplate.class), new ObjectMapper());
        }

        @Override
        public void persistBatch(CounterGranularity baseGranularity, List<BatchItem> batch) {
            if (batch.isEmpty()) {
                return;
            }
            for (CounterBucket bucket : baseGranularity.materialisedBuckets()) {
                for (BatchItem item : batch) {
                    Instant aligned = bucket.align(item.timestamp());
                    store.computeIfAbsent(bucket, k -> new HashMap<>())
                            .computeIfAbsent(aligned, k -> new HashMap<>())
                            .computeIfAbsent(item.counterConfigId(), k -> new HashMap<>())
                            .merge(item.keyHash(), item.delta(), Long::sum);
                }
            }
        }

        Map<CounterBucket, Map<Instant, Map<UUID, Map<String, Long>>>> snapshot() {
            return store;
        }
    }

    private static final class InMemoryQueryRepository extends CounterQueryRepository {
        private final Map<CounterBucket, Map<Instant, Map<UUID, Map<String, Long>>>> store;

        InMemoryQueryRepository(Map<CounterBucket, Map<Instant, Map<UUID, Map<String, Long>>>> store) {
            super(Mockito.mock(NamedParameterJdbcTemplate.class));
            this.store = store;
        }

        @Override
        public List<KeyTotal> fetchRange(
                UUID counterConfigId, CounterBucket bucket, String[] hashes, Instant from, Instant to) {
            Map<Instant, Map<UUID, Map<String, Long>>> bucketData = store.getOrDefault(bucket, Map.of());
            Map<String, Long> totals = new HashMap<>();
            for (String hash : hashes) {
                totals.put(hash, 0L);
            }
            bucketData.forEach((timestamp, configMap) -> {
                if (timestamp.compareTo(from) >= 0 && timestamp.isBefore(to)) {
                    Map<String, Long> values = configMap.get(counterConfigId);
                    if (values != null) {
                        for (String hash : hashes) {
                            totals.merge(hash, values.getOrDefault(hash, 0L), Long::sum);
                        }
                    }
                }
            });
            List<KeyTotal> result = new ArrayList<>();
            for (String hash : hashes) {
                long total = totals.getOrDefault(hash, 0L);
                if (total != 0) {
                    result.add(new KeyTotal(hash, total));
                }
            }
            return result;
        }
    }
}

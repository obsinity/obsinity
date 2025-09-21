package com.obsinity.service.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.entities.AttributeIndexEntity;
import com.obsinity.service.core.entities.EventRegistryEntity;
import com.obsinity.service.core.entities.MetricConfigEntity;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads configuration into an immutable in-memory snapshot:
 *   service -> eventType -> { indexes, counters, histograms }
 *
 * Behavior:
 *  - Full load on startup
 *  - Delta refresh every 5 minutes: only rebuilds services whose max(updatedAt) changed
 *
 * Extra:
 *  - Logs a pretty-printed JSON dump of the config every time it is (re)loaded.
 */
@Service
public class ConfigLoaderService {
    private static final Logger log = LoggerFactory.getLogger(ConfigLoaderService.class);

    private final EntityManager em;
    private final ObjectMapper mapper;
    private final ConfigRegistry registry;

    public ConfigLoaderService(EntityManager em, ObjectMapper mapper, ConfigRegistry registry) {
        this.em = em;
        this.mapper = mapper;
        this.registry = registry;
    }

    // ===== Startup =====
    @PostConstruct
    @Transactional(readOnly = true)
    public void loadOnStartup() {
        log.info("ConfigLoader: performing full snapshot load…");
        RegistrySnapshot snapshot = loadAllSnapshot();
        registry.swap(snapshot);
        logConfig(snapshot, "startup");
    }

    // ===== Periodic refresh =====
    @Scheduled(fixedDelayString = "PT5M")
    @Transactional(readOnly = true)
    public void refreshIfChanged() {
        var curr = registry.current();

        // Build a quick map of cached service "lastUpdated"
        Map<java.util.UUID, Instant> cachedServiceUpdated = curr.services().values().stream()
                .collect(Collectors.toMap(ServiceConfig::serviceId, ServiceConfig::updatedAt));

        List<EventRegistryEntity> allEvents = findAllEventsLite();
        log.info("ConfigLoader: event_registry_cfg rows observed = {}", allEvents.size());
        if (allEvents.isEmpty()) return;

        Map<java.util.UUID, List<EventRegistryEntity>> byService =
                allEvents.stream().collect(Collectors.groupingBy(EventRegistryEntity::getServiceId));

        List<java.util.UUID> changedServices = new ArrayList<>();
        Map<java.util.UUID, Instant> serviceUpdated = new HashMap<>();
        for (var e : byService.entrySet()) {
            Instant maxUpd = e.getValue().stream()
                    .map(EventRegistryEntity::getUpdatedAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(Instant.EPOCH);

            serviceUpdated.put(e.getKey(), maxUpd);

            Instant cached = cachedServiceUpdated.get(e.getKey());
            if (cached == null || maxUpd.isAfter(cached)) {
                changedServices.add(e.getKey());
            }
        }

        if (changedServices.isEmpty()) return;

        Map<java.util.UUID, ServiceConfig> merged = new HashMap<>(curr.services());
        for (java.util.UUID svc : changedServices) {
            Instant svcUpdated = serviceUpdated.getOrDefault(svc, Instant.now());
            ServiceConfig sc = loadSingleService(svc, byService.getOrDefault(svc, List.of()), svcUpdated);
            merged.put(svc, sc);
        }

        RegistrySnapshot next = new RegistrySnapshot(Map.copyOf(merged), Instant.now());
        registry.swap(next);
        logConfig(next, "refresh");
    }

    // ===== Full materialization =====
    private RegistrySnapshot loadAllSnapshot() {
        List<EventRegistryEntity> events = findAllEventsLite();
        Map<java.util.UUID, List<EventRegistryEntity>> byService =
                events.stream().collect(Collectors.groupingBy(EventRegistryEntity::getServiceId));

        Map<java.util.UUID, ServiceConfig> out = new HashMap<>();
        for (var e : byService.entrySet()) {
            Instant maxUpd = e.getValue().stream()
                    .map(EventRegistryEntity::getUpdatedAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(Instant.EPOCH);
            log.info(
                    "ConfigLoader: materializing service={}, events={}, maxUpdated={}",
                    e.getKey(),
                    e.getValue().size(),
                    maxUpd);
            out.put(e.getKey(), loadSingleService(e.getKey(), e.getValue(), maxUpd));
        }
        return new RegistrySnapshot(Map.copyOf(out), Instant.now());
    }

    private ServiceConfig loadSingleService(
            java.util.UUID serviceId, List<EventRegistryEntity> events, Instant serviceUpdatedAt) {
        Map<String, EventTypeConfig> byType = new HashMap<>(Math.max(16, events.size() * 2));

        for (EventRegistryEntity er : events) {
            UUID eventId = er.getId();
            String etype = er.getEventName();
            Instant etUpd = er.getUpdatedAt();

            List<AttributeIndexEntity> idxEntities = findIndexesByEventId(eventId);
            List<MetricConfigEntity> metEntities = findMetricsByEventId(eventId);

            var indexes = idxEntities.stream()
                    .map(i -> new IndexConfig(i.getId(), "attr_index", parseJson(i.getSpecJson())))
                    .toList();

            Map<String, List<MetricConfigEntity>> byKind =
                    metEntities.stream().collect(Collectors.groupingBy(this::metricKind));

            var counters = byKind.getOrDefault("counter", List.of()).stream()
                    .map(m -> new CounterConfig(m.getId(), m.getMetricKey(), parseJson(m.getSpecJson())))
                    .toList();

            var histograms = byKind.getOrDefault("histogram", List.of()).stream()
                    .map(m -> new HistogramConfig(m.getId(), m.getMetricKey(), parseJson(m.getSpecJson())))
                    .toList();

            byType.put(etype, new EventTypeConfig(eventId, etype, etUpd, indexes, counters, histograms));
        }

        String serviceKey = events.isEmpty() ? null : events.get(0).getService();
        return new ServiceConfig(serviceId, serviceKey, serviceUpdatedAt, Map.copyOf(byType));
    }

    // ===== Lightweight queries =====

    private List<EventRegistryEntity> findAllEventsLite() {
        TypedQuery<EventRegistryEntity> q =
                em.createQuery("select e from EventRegistryEntity e", EventRegistryEntity.class);
        return q.getResultList();
    }

    private List<AttributeIndexEntity> findIndexesByEventId(UUID eventId) {
        TypedQuery<AttributeIndexEntity> q = em.createQuery(
                "select i from AttributeIndexEntity i where i.eventId = :eventId", AttributeIndexEntity.class);
        q.setParameter("eventId", eventId);
        return q.getResultList();
    }

    private List<MetricConfigEntity> findMetricsByEventId(UUID eventId) {
        TypedQuery<MetricConfigEntity> q = em.createQuery(
                "select m from MetricConfigEntity m where m.eventId = :eventId", MetricConfigEntity.class);
        q.setParameter("eventId", eventId);
        return q.getResultList();
    }

    // ===== Helpers =====

    private String metricKind(MetricConfigEntity m) {
        String key = Optional.ofNullable(m.getMetricKey()).orElse("").toLowerCase(Locale.ROOT);
        return key.contains("histogram") ? "histogram" : "counter";
    }

    private JsonNode parseJson(String json) {
        try {
            return json == null ? mapper.nullNode() : mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Bad JSON config", e);
        }
    }

    private void logConfig(RegistrySnapshot snapshot, String reason) {
        try {
            String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            log.info("Config snapshot dump after {}:\n{}", reason, pretty);
        } catch (Exception e) {
            log.warn("Failed to log config snapshot after {}: {}", reason, e.toString());
        }
    }
}

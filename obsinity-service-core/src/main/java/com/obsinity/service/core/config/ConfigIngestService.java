package com.obsinity.service.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.config.ConfigMaterializer.ServiceConfigView;
import com.obsinity.service.core.model.config.EventConfig;
import com.obsinity.service.core.model.config.ServiceConfig;
import com.obsinity.service.core.model.config.ServiceConfigResponse;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes service configuration into the in-memory registry. Database writes are limited to
 * ensuring the service row exists so downstream ingestion can resolve service ids.
 */
@Service
public class ConfigIngestService {
    private static final Logger log = LoggerFactory.getLogger(ConfigIngestService.class);

    private final ServicesCatalogRepository servicesRepo;
    private final ConfigRegistry registry;
    private final ConfigMaterializer materializer;

    public ConfigIngestService(ServicesCatalogRepository servicesRepo, ConfigRegistry registry, ObjectMapper mapper) {
        this.servicesRepo = servicesRepo;
        this.registry = registry;
        this.materializer = new ConfigMaterializer(mapper);
    }

    @Transactional
    public ServiceConfigResponse applyConfigUpdate(ServiceConfig request) {
        if (request == null || request.service() == null || request.service().isBlank()) {
            throw new IllegalArgumentException("ServiceConfig.service must be provided");
        }

        ServiceMeta meta = ensureService(request.service().trim());
        ServiceConfigView view =
                materializer.materializeService(request, meta.serviceId(), meta.serviceKey(), Instant.now());

        RegistrySnapshot current = registry.current();
        Map<java.util.UUID, com.obsinity.service.core.config.ServiceConfig> merged =
                new java.util.HashMap<>(current.services());
        merged.put(
                meta.serviceId(),
                new com.obsinity.service.core.config.ServiceConfig(
                        meta.serviceId(), meta.serviceKey(), view.updatedAt(), view.eventTypes()));
        registry.swap(new RegistrySnapshot(Map.copyOf(merged), Instant.now()));

        Counts counts = countArtifacts(request);
        log.info(
                "ConfigIngest: applied in-memory config for service={} events={} counters={} histograms={}",
                request.service(),
                counts.events,
                counts.counters,
                counts.histograms);

        return new ServiceConfigResponse(
                request.snapshotId(), true, counts.events, counts.metricsTotal(), counts.indexes);
    }

    private ServiceMeta ensureService(String serviceKey) {
        UUID serviceId = servicesRepo.findIdByServiceKey(serviceKey);
        if (serviceId == null) {
            String partitionKey = partitionKeyFor(serviceKey);
            servicesRepo.upsertService(serviceKey, partitionKey, "registered via config ingest");
            serviceId = servicesRepo.findIdByServiceKey(serviceKey);
            if (serviceId == null) {
                throw new IllegalStateException("Failed to resolve service id for " + serviceKey);
            }
        }
        return new ServiceMeta(serviceId, serviceKey);
    }

    private static Counts countArtifacts(ServiceConfig request) {
        int events = 0;
        int counters = 0;
        int histograms = 0;
        int indexes = 0;
        if (request.events() != null) {
            for (EventConfig event : request.events()) {
                events++;
                if (event.metrics() != null) {
                    for (var metric : event.metrics()) {
                        String type = metric.type() == null
                                ? ""
                                : metric.type().trim().toUpperCase(Locale.ROOT);
                        if ("HISTOGRAM".equals(type)) histograms++;
                        else counters++;
                    }
                }
                if (event.attributeIndex() != null && event.attributeIndex().specJson() != null) {
                    indexes++;
                }
            }
        }
        return new Counts(events, counters, histograms, indexes);
    }

    private static String partitionKeyFor(String input) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256")
                    .digest(input.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", sha[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash service key", e);
        }
    }

    private record ServiceMeta(UUID serviceId, String serviceKey) {}

    private record Counts(int events, int counters, int histograms, int indexes) {
        int metricsTotal() {
            return counters + histograms;
        }
    }
}

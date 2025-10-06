package com.obsinity.service.core.config.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.config.ConfigMaterializer;
import com.obsinity.service.core.config.ConfigMaterializer.ServiceConfigView;
import com.obsinity.service.core.config.ConfigRegistry;
import com.obsinity.service.core.config.RegistrySnapshot;
import com.obsinity.service.core.model.config.ServiceConfig;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConfigInitCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ConfigInitCoordinator.class);

    private final boolean enabled;
    private final String location; // e.g. "classpath:/service-definitions/" OR "file:/path/to/service-definitions/"
    private final ServicesCatalogRepository servicesRepo;
    private final ConfigRegistry registry;
    private final ConfigMaterializer materializer;
    private final ReentrantLock lock = new ReentrantLock();

    public ConfigInitCoordinator(
            ServicesCatalogRepository servicesRepo,
            ConfigRegistry registry,
            ObjectMapper objectMapper,
            @Value("${obsinity.config.init.enabled:true}") boolean enabled,
            @Value("${obsinity.config.init.location:classpath:/service-definitions/}") String location) {
        this.servicesRepo = servicesRepo;
        this.registry = registry;
        this.materializer = new ConfigMaterializer(objectMapper);
        this.enabled = enabled;
        this.location = location;
        if (log.isDebugEnabled()) {
            log.debug("ConfigInitCoordinator constructed: enabled={}, location={}", enabled, location);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info(
                "Config init startup: enabled={}, location={}, cron={}",
                enabled,
                location,
                System.getProperty("obsinity.config.init.cron", "0 * * * * *"));
        runOnce("startup");
    }

    /** Every minute (default). */
    @Scheduled(cron = "${obsinity.config.init.cron:0 * * * * *}")
    public void scheduled() {
        if (log.isDebugEnabled()) {
            log.debug("Config init scheduled tick fired");
        }
        runOnce("scheduled");
    }

    private void runOnce(String reason) {
        if (!enabled) {
            log.debug("Config init disabled; skip ({})", reason);
            return;
        }
        if (!lock.tryLock()) {
            log.debug("Config init already running; skip ({})", reason);
            return;
        }
        long t0 = System.nanoTime();
        try {
            log.debug("Config init starting (reason={}, location={})", reason, location);
            List<ServiceConfig> models = loadServices(location);
            if (models.isEmpty()) {
                log.debug("No ServiceConfig found at {}", location);
                return;
            }

            Map<java.util.UUID, com.obsinity.service.core.config.ServiceConfig> merged = new HashMap<>();
            for (ServiceConfig model : models) {
                if (model == null || model.service() == null || model.service().isBlank()) continue;
                ServiceMeta meta = ensureService(model.service());
                ServiceConfigView view =
                        materializer.materializeService(model, meta.serviceId(), meta.serviceKey(), Instant.now());
                merged.put(
                        meta.serviceId(),
                        new com.obsinity.service.core.config.ServiceConfig(
                                meta.serviceId(), meta.serviceKey(), view.updatedAt(), view.eventTypes()));
            }

            RegistrySnapshot snapshot = new RegistrySnapshot(Map.copyOf(merged), Instant.now());
            registry.swap(snapshot);
            log.info("Config init ({}) loaded {} service definitions", reason, merged.size());
        } catch (Exception ex) {
            log.warn("Config init failed ({}): {}", reason, ex.getMessage());
            log.debug("Config init failure stacktrace", ex);
        } finally {
            lock.unlock();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            log.debug("Config init finished (reason={}) in {} ms", reason, ms);
        }
    }

    private List<ServiceConfig> loadServices(String loc) throws Exception {
        String resolved = loc;
        if (!resolved.matches("^[a-zA-Z]+:.*")) {
            java.nio.file.Path p =
                    java.nio.file.Paths.get(resolved).toAbsolutePath().normalize();
            resolved = "file:" + p;
        }
        if (!resolved.endsWith("/")) resolved = resolved + "/";
        log.info("Config init: resolved base location={}", resolved);
        return new ResourceConfigSource(resolved).load();
    }

    private ServiceMeta ensureService(String serviceKey) {
        String key = serviceKey.trim();
        java.util.UUID serviceId = servicesRepo.findIdByServiceKey(key);
        if (serviceId == null) {
            String partitionKey = partitionKeyFor(key);
            servicesRepo.upsertService(key, partitionKey, "Loaded from service definitions");
            serviceId = servicesRepo.findIdByServiceKey(key);
            if (serviceId == null) {
                throw new IllegalStateException("Unable to register service " + key);
            }
        }
        return new ServiceMeta(serviceId, key);
    }

    private static String partitionKeyFor(String input) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256")
                    .digest(input.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8 * 2);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", sha[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute hash for service key", e);
        }
    }

    private record ServiceMeta(java.util.UUID serviceId, String serviceKey) {}
}

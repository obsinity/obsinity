package com.obsinity.service.core.config.init;

import com.obsinity.service.core.config.ConfigIngestService;
import com.obsinity.service.core.model.config.ServiceConfig;
import java.util.List;
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
    private final String location; // e.g. "classpath:/init-config/" OR "file:/path/to/init-config/"
    private final ConfigIngestService ingestService;
    private final ReentrantLock lock = new ReentrantLock();

    public ConfigInitCoordinator(
            ConfigIngestService ingestService,
            @Value("${obsinity.config.init.enabled:true}") boolean enabled,
            @Value("${obsinity.config.init.location:classpath:/init-config/}") String location) {
        this.ingestService = ingestService;
        this.enabled = enabled;
        this.location = location;
        if (log.isDebugEnabled()) {
            log.debug("ConfigInitCoordinator constructed: enabled={}, location={}", enabled, location);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (log.isInfoEnabled()) {
            log.info(
                    "Config init startup: enabled={}, location={}, cron={}",
                    enabled,
                    location,
                    System.getProperty("obsinity.config.init.cron", "0 * * * * *"));
        }
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
            log.debug("Config init lock acquired (reason={})", reason);
            log.debug("Config init starting (reason={}, location={})", reason, location);
            List<ServiceConfig> services = loadServices(location);
            log.debug("Config init discovered {} service configs", services.size());
            if (services.isEmpty()) {
                log.debug("No ServiceConfig found at {}", location);
                return;
            }
            int ok = 0;
            for (ServiceConfig sc : services) {
                try {
                    log.debug(
                            "Applying ServiceConfig: service={}, events={} (snapshotId={})",
                            sc.service(),
                            sc.events() == null ? 0 : sc.events().size(),
                            sc.snapshotId());
                    ingestService.applyConfigUpdate(sc);
                    ok++;
                } catch (Exception ex) {
                    log.warn("Config apply failed for {}: {}", sc.service(), ex.getMessage());
                }
            }
            log.info("Config init ({}) applied {} service configs from {}", reason, ok, location);
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
        // Ensure the resolver gets an explicit prefix; default to classpath if none given.
        String l = loc;
        log.debug("Config init: incoming base location={}", l);
        if (!l.matches("^[a-zA-Z]+:.*")) { // no scheme
            // treat bare paths as file system locations
            java.nio.file.Path p = java.nio.file.Paths.get(l).toAbsolutePath().normalize();
            l = "file:" + p.toString();
        }
        // Ensure trailing slash for the "**/*.yml" pattern
        if (!l.endsWith("/")) l = l + "/";
        log.info("Config init: resolved base location={}", l);
        log.debug("Config init: invoking ResourceConfigSource for base={}", l);
        return new ResourceConfigSource(l).load();
    }
}

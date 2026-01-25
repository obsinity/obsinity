package com.obsinity.controller.admin;

import com.obsinity.service.core.config.ConfigIngestService;
import com.obsinity.service.core.model.config.ServiceConfig;
import com.obsinity.service.core.model.config.ServiceConfigResponse;
import com.obsinity.service.core.state.transition.health.TransitionHealthSummaryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ConfigIngestService ingestService;
    private final ServiceConfigArchiveLoader archiveLoader;
    private final TransitionHealthSummaryService healthSummaryService;

    public AdminController(
            ConfigIngestService ingestService,
            ServiceConfigArchiveLoader archiveLoader,
            TransitionHealthSummaryService healthSummaryService) {
        this.ingestService = ingestService;
        this.archiveLoader = archiveLoader;
        this.healthSummaryService = healthSummaryService;
    }

    /**
     * Lightweight ping for admin readiness of the config ingest surface.
     */
    @GetMapping(path = "/config/ready", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ready() {
        return ResponseEntity.ok("OK");
    }

    @GetMapping(path = "/state/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransitionHealthSummaryService.TransitionHealthSummary> transitionHealth() {
        return ResponseEntity.ok(healthSummaryService.summary());
    }

    /**
     * Ingest a full service configuration snapshot as JSON.
     * Phase-1: create/update only (no deletes). Transactional on the server.
     */
    @PostMapping(
            path = "/config/service",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceConfigResponse> ingestServiceConfig(@RequestBody ServiceConfig config) {
        ServiceConfigResponse result = ingestService.applyConfigUpdate(config);
        return ResponseEntity.ok(result);
    }

    /**
     * Accepts a .tar.gz with layout:
     *   services/<service>/events/<event>/event.yaml
     *   services/<service>/events/<event>/metrics/{counters,histograms,gauges}/*.yaml
     *
     * Supports either application/gzip raw body OR multipart/form-data with a "file".
     * Returns the same response as JSON ingest after translating the archive to a ServiceConfig.
     */
    @PostMapping(
            path = "/configs/import",
            consumes = {"application/gzip", MediaType.MULTIPART_FORM_DATA_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceConfigResponse> importArchive(
            @RequestParam(name = "mode", defaultValue = "upsert") String mode,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestBody(required = false) byte[] rawBody)
            throws Exception {
        byte[] bytes = (file != null) ? file.getBytes() : (rawBody != null ? rawBody : new byte[0]);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("No archive content provided");
        }
        ServiceConfig cfg = archiveLoader.loadFromTarGz(bytes);
        ServiceConfigResponse result = ingestService.applyConfigUpdate(cfg);
        return ResponseEntity.ok(result);
    }
}

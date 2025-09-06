package com.obsinity.controller.rest;

import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.spi.EventIngestService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class IngestController {
    private final EventIngestService ingest;

    public IngestController(EventIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/ingest/event")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> ingestOne(@Valid @RequestBody EventEnvelope e) {
        int stored = ingest.ingestOne(e);
        return Map.of("status", stored == 1 ? "stored" : "duplicate");
    }

    @PostMapping("/ingest/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> ingestBatch(@Valid @RequestBody List<EventEnvelope> events) {
        int stored = ingest.ingestBatch(events);
        return Map.of("stored", stored, "duplicates", Math.max(0, events.size() - stored));
    }
}

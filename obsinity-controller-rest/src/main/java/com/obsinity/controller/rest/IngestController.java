package com.obsinity.controller.rest;

import org.springframework.web.bind.annotation.*;

/** Accepts telemetry events over HTTP. */
@RestController
@RequestMapping("/ingest")
public class IngestController {
    @PostMapping
    public void ingest(@RequestBody String payload) {
        // TODO: parse and dispatch to service-core
    }
}

package com.obsinity.controller.otlp;

import org.springframework.web.bind.annotation.*;

/** OTLP translation stub (HTTP mapping only for now). */
@RestController
@RequestMapping("/otlp")
public class OtlpIngestController {
    @PostMapping("/v1/traces")
    public void traces(@RequestBody byte[] body) {
        // TODO: translate OTLP to internal model and dispatch
    }
}

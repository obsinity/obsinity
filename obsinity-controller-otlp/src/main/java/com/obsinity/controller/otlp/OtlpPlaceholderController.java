package com.obsinity.controller.otlp;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/otlp")
public class OtlpPlaceholderController {
    @PostMapping("/traces")
    public Map<String, String> traces() {
        return Map.of("status", "NOT_IMPLEMENTED");
    }

    @PostMapping("/metrics")
    public Map<String, String> metrics() {
        return Map.of("status", "NOT_IMPLEMENTED");
    }

    @PostMapping("/logs")
    public Map<String, String> logs() {
        return Map.of("status", "NOT_IMPLEMENTED");
    }
}

package com.obsinity.controller.rest.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/grafana", produces = MediaType.APPLICATION_JSON_VALUE)
public class GrafanaStaticResponseController {

    private final ResourceLoader resourceLoader;
    private final String staticResponseLocation;
    private final ObjectMapper objectMapper;
    private final AtomicReference<CachedResponse> cached = new AtomicReference<>();

    public GrafanaStaticResponseController(
            ResourceLoader resourceLoader,
            @Value("${obsinity.grafana.static-response:classpath:grafana/static-response.json}")
                    String staticResponseLocation,
            ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.staticResponseLocation = staticResponseLocation;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(
            path = "/static",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> staticResponse() {
        Resource resource = resourceLoader.getResource(staticResponseLocation);
        if (!resource.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Static Grafana response not found\"}");
        }
        try {
            String payload = loadPayload(resource);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(payload);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Failed to read static Grafana response\"}");
        }
    }

    @RequestMapping(
            path = "/static-rows",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> staticRows() {
        Resource resource = resourceLoader.getResource(staticResponseLocation);
        if (!resource.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Static Grafana response not found\"}");
        }
        try {
            String payload = loadPayload(resource);
            JsonNode root = objectMapper.readTree(payload);
            JsonNode rows = root.path("rows");
            if (rows.isMissingNode() || !rows.isArray()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"rows array not found in static response\"}");
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(rows.toString());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Failed to read static Grafana rows\"}");
        }
    }

    private String loadPayload(Resource resource) throws IOException {
        if (resource.isFile()) {
            File file = resource.getFile();
            long lastModified = file.lastModified();
            CachedResponse existing = cached.get();
            if (existing != null
                    && existing.lastModified == lastModified
                    && file.getPath().equals(existing.path)) {
                return existing.payload;
            }
            String payload = readResource(resource);
            cached.set(new CachedResponse(file.getPath(), lastModified, payload));
            return payload;
        }
        return readResource(resource);
    }

    private String readResource(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
    }

    private record CachedResponse(String path, long lastModified, String payload) {}
}

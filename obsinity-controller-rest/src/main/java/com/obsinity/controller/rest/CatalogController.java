package com.obsinity.controller.rest;

import com.obsinity.service.core.catalog.EventCatalogService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final EventCatalogService catalog;

    public CatalogController(EventCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/event-type/{type}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createType(@PathVariable String type, @RequestBody Map<String, Object> body) {
        String desc = body == null ? "" : String.valueOf(body.getOrDefault("description", ""));
        catalog.ensureType(type, desc);
        return Map.of("status", "ok", "type", type);
    }

    @PostMapping("/event-type/{type}/index")
    public Map<String, Object> addIndex(@PathVariable String type, @RequestBody Map<String, String> body) {
        catalog.addIndexPath(type, body.get("path"));
        return Map.of("status", "ok", "type", type, "path", body.get("path"));
    }

    @GetMapping("/event-type")
    public List<Map<String, Object>> listTypes() {
        return catalog.listTypes();
    }
}

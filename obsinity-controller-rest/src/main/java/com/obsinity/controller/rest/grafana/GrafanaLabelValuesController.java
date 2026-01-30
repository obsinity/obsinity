package com.obsinity.controller.rest.grafana;

import com.obsinity.service.core.repo.AttributeValuesRepository;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/grafana", produces = MediaType.APPLICATION_JSON_VALUE)
public class GrafanaLabelValuesController {

    private final AttributeValuesRepository repo;
    private final ServicesCatalogRepository services;

    public GrafanaLabelValuesController(AttributeValuesRepository repo, ServicesCatalogRepository services) {
        this.repo = repo;
        this.services = services;
    }

    @GetMapping(path = "/label-values")
    public List<String> listLabelValues(
            @RequestParam("label") String label,
            @RequestParam("serviceKey") String serviceKey,
            @RequestParam(value = "limit", required = false, defaultValue = "200") int limit) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalArgumentException("serviceKey is required");
        }
        if (limit <= 0 || limit > 2000) {
            limit = 200;
        }

        String partitionKey = services.findPartitionKeyByServiceKey(serviceKey);
        String svc = (partitionKey == null || partitionKey.isBlank()) ? serviceKey : partitionKey;

        List<Map<String, Object>> rows = repo.list(svc, label, null, 0, limit);
        return rows.stream()
                .map(row -> row.get("attr_value"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}

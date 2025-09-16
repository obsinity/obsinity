package com.obsinity.controller.rest;

import com.obsinity.service.core.repo.AttributeValuesRepository;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
public class AttributeValuesController {

    private final AttributeValuesRepository repo;
    private final ServicesCatalogRepository services;
    private final String embeddedKey;
    private final String linksKey;
    private static final Logger log = LoggerFactory.getLogger(AttributeValuesController.class);

    public AttributeValuesController(
            AttributeValuesRepository repo,
            ServicesCatalogRepository services,
            @Value("${obsinity.api.hal.embedded:embedded}") String embeddedKey,
            @Value("${obsinity.api.hal.links:links}") String linksKey) {
        this.repo = repo;
        this.services = services;
        this.embeddedKey = embeddedKey;
        this.linksKey = linksKey;
    }

    @GetMapping(value = "/attributes/{service}/{attrName:.+}/values", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> list(
            @PathVariable("service") String service,
            @PathVariable("attrName") String attrName,
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "offset", required = false, defaultValue = "0") long offset,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        if (service == null || service.isBlank()) throw new IllegalArgumentException("service is required");
        if (attrName == null || attrName.isBlank()) throw new IllegalArgumentException("attrName is required");
        if (limit <= 0 || limit > 1000) limit = 50;
        if (offset < 0) offset = 0;

        String shortKey = services.findShortKeyByServiceKey(service);
        String svc = (shortKey == null || shortKey.isBlank()) ? service : shortKey;

        if (log.isInfoEnabled()) {
            log.info(
                    "GET attribute values: service={}, attrName={}, prefix={}, offset={}, limit={}",
                    service,
                    attrName,
                    prefix,
                    offset,
                    limit);
        }

        long total = repo.count(svc, attrName, prefix);
        List<Map<String, Object>> rows = repo.list(svc, attrName, prefix, offset, limit);
        List<Map<String, Object>> data =
                rows.stream().map(this::camelizeValueRow).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", data.size());
        out.put("total", total);
        out.put("limit", limit);
        out.put("offset", offset);
        Map<String, Object> embedded = new LinkedHashMap<>();
        embedded.put("values", data);
        out.put(embeddedKey, embedded);
        out.put(linksKey, buildLinksForValues(service, attrName, prefix, offset, limit, data.size(), total));
        return out;
    }

    @GetMapping(value = "/attributes/{service}/names", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> listNames(
            @PathVariable("service") String service,
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "offset", required = false, defaultValue = "0") long offset,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        if (service == null || service.isBlank()) throw new IllegalArgumentException("service is required");
        if (limit <= 0 || limit > 1000) limit = 100;
        if (offset < 0) offset = 0;

        String shortKey = services.findShortKeyByServiceKey(service);
        String svc = (shortKey == null || shortKey.isBlank()) ? service : shortKey;

        if (log.isInfoEnabled()) {
            log.info("GET attribute names: service={}, prefix={}, offset={}, limit={}", service, prefix, offset, limit);
        }

        long total = repo.countNames(svc, prefix);
        List<Map<String, Object>> rows = repo.listNames(svc, prefix, offset, limit);
        List<Map<String, Object>> data =
                rows.stream().map(this::camelizeNameRow).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", data.size());
        out.put("total", total);
        out.put("limit", limit);
        out.put("offset", offset);
        Map<String, Object> embedded = new LinkedHashMap<>();
        embedded.put("attributes", data);
        out.put(embeddedKey, embedded);
        out.put(linksKey, buildLinksForNames(service, prefix, offset, limit, data.size(), total));
        return out;
    }

    private Map<String, Object> camelizeValueRow(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attribute", row.get("attr_value"));
        m.put("seenCount", row.get("seen_count"));
        m.put("firstSeen", row.get("first_seen"));
        m.put("lastSeen", row.get("last_seen"));
        return m;
    }

    private Map<String, Object> camelizeNameRow(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attribute", row.get("attr_name"));
        m.put("valueCount", row.get("value_count"));
        return m;
    }

    private Map<String, Object> buildLinksForValues(
            String service, String attrName, String prefix, long offset, int limit, long count, long total) {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", linkValues(service, attrName, prefix, offset, limit));
        links.put("first", linkValues(service, attrName, prefix, 0, limit));
        long lastOffset = (total <= 0) ? 0 : Math.max(0, ((total - 1) / (long) limit) * (long) limit);
        links.put("last", linkValues(service, attrName, prefix, lastOffset, limit));
        if (offset > 0) links.put("prev", linkValues(service, attrName, prefix, Math.max(0, offset - limit), limit));
        long nextOffset = offset + limit;
        if ((total > 0 && offset + count < total) || (total == 0 && count >= limit)) {
            links.put("next", linkValues(service, attrName, prefix, nextOffset, limit));
        }
        return links;
    }

    private Map<String, Object> buildLinksForNames(
            String service, String prefix, long offset, int limit, long count, long total) {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", linkNames(service, prefix, offset, limit));
        links.put("first", linkNames(service, prefix, 0, limit));
        long lastOffset = (total <= 0) ? 0 : Math.max(0, ((total - 1) / (long) limit) * (long) limit);
        links.put("last", linkNames(service, prefix, lastOffset, limit));
        if (offset > 0) links.put("prev", linkNames(service, prefix, Math.max(0, offset - limit), limit));
        long nextOffset = offset + limit;
        if ((total > 0 && offset + count < total) || (total == 0 && count >= limit)) {
            links.put("next", linkNames(service, prefix, nextOffset, limit));
        }
        return links;
    }

    private Map<String, Object> linkValues(String service, String attrName, String prefix, long offset, int limit) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put(
                "href",
                String.format(
                        "/api/catalog/attributes/%s/%s/values?%s",
                        urlEnc(service), urlEnc(attrName), qp(prefix, offset, limit)));
        link.put("method", "GET");
        return link;
    }

    private Map<String, Object> linkNames(String service, String prefix, long offset, int limit) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put(
                "href",
                String.format("/api/catalog/attributes/%s/names?%s", urlEnc(service), qp(prefix, offset, limit)));
        link.put("method", "GET");
        return link;
    }

    private String qp(String prefix, long offset, int limit) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) {
            sb.append("prefix=").append(urlEnc(prefix)).append("&");
        }
        sb.append("offset=").append(offset).append("&limit=").append(limit);
        return sb.toString();
    }

    private String urlEnc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

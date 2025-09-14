package com.obsinity.service.core.config.init;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.obsinity.service.core.model.config.EventConfig;
import com.obsinity.service.core.model.config.EventIndexConfig;
import com.obsinity.service.core.model.config.MetricConfig;
import com.obsinity.service.core.model.config.ServiceConfig;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class ResourceConfigSource {

    private static final Logger log = LoggerFactory.getLogger(ResourceConfigSource.class);

    private final String baseLocation; // e.g. "classpath:/init-config/"

    public ResourceConfigSource(String baseLocation) {
        this.baseLocation = baseLocation.endsWith("/") ? baseLocation : baseLocation + "/";
    }

    public List<ServiceConfig> load() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        String ymlPattern = baseLocation + "**/*.yml";
        String yamlPattern = baseLocation + "**/*.yaml";
        String jsonPattern = baseLocation + "**/*.json";

        Resource[] yml = resolver.getResources(ymlPattern);
        Resource[] yaml = resolver.getResources(yamlPattern);
        Resource[] json = resolver.getResources(jsonPattern);

        log.debug(
                "ResourceConfigSource scanning: base={}, patterns=[{}, {}, {}], counts=[{}, {}, {}]",
                baseLocation,
                ymlPattern,
                yamlPattern,
                jsonPattern,
                yml.length,
                yaml.length,
                json.length);

        List<ServiceConfig> all = new ArrayList<>();
        for (Resource r : Stream.of(yml, yaml, json).flatMap(Arrays::stream).toList()) {
            if (!r.exists() || !r.isReadable()) continue;
            String fname = safeName(r);
            log.info("Init-config: processing {}", fname);

            String text;
            try (InputStream in = r.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            boolean parsedSomething = false;
            // First: try ServiceConfig formats
            try {
                List<ServiceConfig> parsed = parseServiceConfigText(text, fname);
                if (!parsed.isEmpty()) {
                    log.info("Init-config: parsed {} ServiceConfig item(s) from {}", parsed.size(), fname);
                    all.addAll(parsed);
                    parsedSomething = true;
                }
            } catch (Exception ex) {
                // Common for CRD files with apiVersion/kind; fallback handles it.
                log.debug(
                        "Init-config: ServiceConfig parse failed for {} ({}). Will try CRD fallback.",
                        fname,
                        ex.toString());
            }

            // Fallback: CRD (apiVersion/kind)
            if (!parsedSomething) {
                List<ServiceConfig> crd = parseCrdDocuments(text, fname);
                if (!crd.isEmpty()) {
                    log.info("Init-config: parsed {} CRD-derived ServiceConfig item(s) from {}", crd.size(), fname);
                    all.addAll(crd);
                    parsedSomething = true;
                }
            }

            if (!parsedSomething) {
                log.warn("Init-config: no usable config parsed from {} (neither ServiceConfig nor CRD)", fname);
            }
        }

        // merge per service key (last wins)
        Map<String, ServiceConfig> merged = new LinkedHashMap<>();
        for (ServiceConfig sc : all) {
            if (sc == null || sc.service() == null || sc.service().isBlank()) continue;
            merged.merge(sc.service(), sc, ResourceConfigSource::merge);
        }
        return new ArrayList<>(merged.values());
    }

    private static String safeName(Resource r) {
        try {
            return r.getFilename();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    // ===== CRD support (Event/Metric YAML -> ServiceConfig) =====

    private List<ServiceConfig> parseCrdDocuments(String text, String fileNameHint) {
        try {
            Map<String, Object> root = chooseMapper(fileNameHint).readValue(text, new TypeReference<>() {});
            Object api = root.get("apiVersion");
            Object kind = root.get("kind");
            if (api == null || kind == null) return List.of();

            String apiVersion = String.valueOf(api).trim().toLowerCase(Locale.ROOT);
            if (!apiVersion.equals("obsinity/v1")) {
                log.warn("Unsupported apiVersion '{}' in {} — only obsinity/v1 is supported", apiVersion, fileNameHint);
                return List.of();
            }

            String kindStr = String.valueOf(kind).trim().toLowerCase(Locale.ROOT);
            if ("event".equals(kindStr)) {
                ServiceConfig sc = fromEventCrd(root);
                return (sc == null) ? List.of() : List.of(sc);
            }
            if ("metriccounter".equals(kindStr) || "metrichistogram".equals(kindStr)) {
                ServiceConfig sc = fromMetricCrd(root, kindStr);
                return (sc == null) ? List.of() : List.of(sc);
            }
            return List.of();
        } catch (Exception e) {
            log.debug("CRD parse skipped for {}: {}", fileNameHint, e.toString());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private ServiceConfig fromEventCrd(Map<String, Object> root) {
        try {
            Map<String, Object> meta = (Map<String, Object>) root.getOrDefault("metadata", Map.of());
            Map<String, Object> spec = (Map<String, Object>) root.getOrDefault("spec", Map.of());
            Map<String, Object> schema = (Map<String, Object>) spec.getOrDefault("schema", Map.of());

            String service = string(meta.get("service"));
            String eventName = string(meta.get("name"));
            if (service == null || eventName == null) return null;

            String category = null;
            String subCategory = null;
            Object cat = meta.get("category");
            if (cat == null) {
                Object labelsObj = meta.get("labels");
                if (labelsObj instanceof Map<?, ?> labels) {
                    Object c = labels.get("category");
                    if (c != null) cat = c;
                }
            }
            category = string(cat);
            // prefer explicit metadata.subCategory; fallback to metadata.labels.subCategory
            Object subCat = meta.get("subCategory");
            if (subCat == null) {
                Object labelsObj = meta.get("labels");
                if (labelsObj instanceof Map<?, ?> labels) {
                    Object sc = labels.get("subCategory");
                    if (sc != null) subCat = sc;
                }
            }
            subCategory = string(subCat);

            // Validate: reserved standard fields must NOT be declared in schema.properties
            if (declaresReservedFields(schema)) {
                log.warn(
                        "Event CRD '{}' declares reserved standard fields in schema.properties; remove them (eventId, timestamp, name, kind, trace, correlationId)",
                        eventName);
                return null;
            }

            // Build attribute index spec from JSON-schema-like 'properties' using index: true flags
            List<String> paths = deriveIndexedPaths(schema);
            Map<String, Object> attrSpec = new LinkedHashMap<>();
            if (schema != null && !schema.isEmpty()) {
                attrSpec.put("schema", schema);
            }
            attrSpec.put("indexed", paths);

            EventIndexConfig attrIndex = new EventIndexConfig(UUID.randomUUID(), attrSpec, null);
            EventConfig event = new EventConfig(null, eventName, null, category, subCategory, List.of(), attrIndex);

            return ServiceConfig.of(service, "crd:" + eventName, List.of(event));
        } catch (Exception e) {
            log.debug("Failed to convert Event CRD to ServiceConfig: {}", e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean declaresReservedFields(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return false;
        Object propsObj = schema.get("properties");
        if (!(propsObj instanceof Map<?, ?> props)) return false;
        Set<String> reserved = Set.of("eventId", "timestamp", "name", "kind", "trace", "correlationId");
        for (Object k : props.keySet()) {
            String key = String.valueOf(k);
            if (reserved.contains(key)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<String> deriveIndexedPaths(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        walkSchema("", schema, false, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void walkSchema(String prefix, Map<String, Object> node, boolean inheritedIndex, List<String> out) {
        boolean nodeIndex = inheritedIndex || Boolean.TRUE.equals(node.get("index"));
        Object typeObj = node.get("type");
        String type = (typeObj == null) ? null : String.valueOf(typeObj);

        if ("object".equals(type)) {
            Map<String, Object> props = (Map<String, Object>) node.get("properties");
            if (props != null) {
                for (Map.Entry<String, Object> e : props.entrySet()) {
                    String key = e.getKey();
                    Object val = e.getValue();
                    if (!(val instanceof Map)) continue;
                    Map<String, Object> child = (Map<String, Object>) val;
                    String next = prefix.isEmpty() ? key : prefix + "." + key;
                    walkSchema(next, child, nodeIndex, out);
                }
            }
        } else if ("array".equals(type)) {
            Object items = node.get("items");
            if (items instanceof Map<?, ?> m) {
                String next = prefix + "[]";
                walkSchema(next, (Map<String, Object>) m, nodeIndex, out);
            }
        } else {
            // scalar leaf
            if (nodeIndex && !prefix.isEmpty()) out.add(prefix);
        }
    }

    private static String string(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /* ---------- parsing (single file) ---------- */

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<ServiceConfig> parseServiceConfigText(String txt, String fileNameHint) throws Exception {
        if (txt == null) return List.of();
        txt = txt.trim();
        if (txt.isEmpty()) return List.of();

        ObjectMapper mapper = chooseMapper(fileNameHint);

        try {
            Map<String, Object> root = mapper.readValue(txt, new TypeReference<>() {});
            if (root.containsKey("services")) {
                List<ServiceConfig> arr =
                        mapper.convertValue(root.get("services"), new TypeReference<List<ServiceConfig>>() {});
                return arr == null ? List.of() : arr;
            }
        } catch (Exception ignore) {
            /* not wrapper; continue */
        }

        try {
            return mapper.readValue(txt, new TypeReference<List<ServiceConfig>>() {});
        } catch (Exception notArray) {
            ServiceConfig one = mapper.readValue(txt, ServiceConfig.class);
            return (one == null) ? List.of() : List.of(one);
        }
    }

    private static ObjectMapper chooseMapper(String fileNameHint) {
        String n = Optional.ofNullable(fileNameHint).orElse("").toLowerCase(Locale.ROOT);
        return n.endsWith(".json") ? JSON : YAML;
    }

    private static ServiceConfig merge(ServiceConfig a, ServiceConfig b) {
        // Merge events by key; when keys collide, merge metrics lists (by name) and prefer b's attributeIndex if
        // present
        Map<String, com.obsinity.service.core.model.config.EventConfig> map = new LinkedHashMap<>();
        if (a.events() != null) {
            for (var e : a.events()) {
                String key = (e.eventNorm() != null && !e.eventNorm().isBlank())
                        ? e.eventNorm()
                        : (e.eventName() == null ? "" : e.eventName().toLowerCase(Locale.ROOT));
                if (!key.isBlank()) map.put(key, e);
            }
        }
        if (b.events() != null) {
            for (var e : b.events()) {
                String key = (e.eventNorm() != null && !e.eventNorm().isBlank())
                        ? e.eventNorm()
                        : (e.eventName() == null ? "" : e.eventName().toLowerCase(Locale.ROOT));
                if (key.isBlank()) continue;
                if (!map.containsKey(key)) {
                    map.put(key, e);
                } else {
                    var existing = map.get(key);
                    Map<String, MetricConfig> byName = new LinkedHashMap<>();
                    if (existing.metrics() != null) {
                        for (MetricConfig m : existing.metrics()) {
                            if (m != null && m.name() != null) byName.put(m.name(), m);
                        }
                    }
                    if (e.metrics() != null) {
                        for (MetricConfig m : e.metrics()) {
                            if (m != null && m.name() != null) byName.put(m.name(), m); // b overrides a
                        }
                    }
                    List<MetricConfig> mergedMetrics = new ArrayList<>(byName.values());
                    var attr = (e.attributeIndex() != null) ? e.attributeIndex() : existing.attributeIndex();
                    String cat = (e.category() != null && !e.category().isBlank()) ? e.category() : existing.category();
                    String sub = (e.subCategory() != null && !e.subCategory().isBlank())
                            ? e.subCategory()
                            : existing.subCategory();
                    map.put(
                            key,
                            new com.obsinity.service.core.model.config.EventConfig(
                                    existing.uuid(),
                                    existing.eventName(),
                                    existing.eventNorm(),
                                    cat,
                                    sub,
                                    mergedMetrics,
                                    attr));
                }
            }
        }
        return ServiceConfig.of(a.service(), a.snapshotId(), new ArrayList<>(map.values()));
    }

    @SuppressWarnings("unchecked")
    private ServiceConfig fromMetricCrd(Map<String, Object> root, String kindLower) {
        try {
            Map<String, Object> meta = (Map<String, Object>) root.getOrDefault("metadata", Map.of());
            Map<String, Object> spec = (Map<String, Object>) root.getOrDefault("spec", Map.of());

            String service = string(meta.get("service"));
            String eventName = string(meta.get("event"));
            String metricName = string(meta.get("name"));
            if (service == null || eventName == null || metricName == null) return null;

            String type = "metriccounter".equals(kindLower) ? "COUNTER" : "HISTOGRAM";

            // keyedKeys from key.dimensions
            Map<String, Object> key = (Map<String, Object>) spec.getOrDefault("key", Map.of());
            List<String> dimensions = new ArrayList<>();
            Object dimsObj = key.get("dimensions");
            if (dimsObj instanceof List<?> l) {
                for (Object d : l) {
                    String s = string(d);
                    if (s != null) dimensions.add(s);
                }
            }

            // rollups from aggregation.windowing.granularities
            Map<String, Object> aggregation = (Map<String, Object>) spec.getOrDefault("aggregation", Map.of());
            Map<String, Object> windowing = (Map<String, Object>) aggregation.getOrDefault("windowing", Map.of());
            List<String> granularities = new ArrayList<>();
            Object grans = windowing.get("granularities");
            if (grans instanceof List<?> l) {
                for (Object g : l) {
                    String s = string(g);
                    if (s != null) granularities.add(s);
                }
            }

            // specJson: include relevant parts of spec verbatim
            Map<String, Object> specJson = new LinkedHashMap<>();
            if (spec.containsKey("value")) specJson.put("value", spec.get("value"));
            if (spec.containsKey("buckets")) specJson.put("buckets", spec.get("buckets"));
            if (spec.containsKey("fold")) specJson.put("fold", spec.get("fold"));
            if (!aggregation.isEmpty()) specJson.put("aggregation", aggregation);
            if (key.containsKey("dimensions")) specJson.put("key", Map.of("dimensions", dimensions));
            if (spec.containsKey("attributeMapping")) specJson.put("attributeMapping", spec.get("attributeMapping"));
            if (spec.containsKey("filters")) specJson.put("filters", spec.get("filters"));

            MetricConfig m = new MetricConfig(
                    null,
                    metricName,
                    type,
                    specJson,
                    null,
                    dimensions,
                    granularities,
                    Map.of(),
                    null,
                    null,
                    null,
                    null,
                    null);

            com.obsinity.service.core.model.config.EventConfig ev =
                    new com.obsinity.service.core.model.config.EventConfig(
                            null, eventName, null, null, null, List.of(m), null);
            return ServiceConfig.of(service, "crd:" + metricName, List.of(ev));
        } catch (Exception e) {
            log.debug("Failed to convert Metric CRD to ServiceConfig: {}", e.toString());
            return null;
        }
    }
}

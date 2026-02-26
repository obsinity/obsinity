package com.obsinity.service.core.config.init;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.obsinity.service.core.model.config.EventConfig;
import com.obsinity.service.core.model.config.EventIndexConfig;
import com.obsinity.service.core.model.config.MetricConfig;
import com.obsinity.service.core.model.config.RatioQueryConfig;
import com.obsinity.service.core.model.config.ServiceConfig;
import com.obsinity.service.core.model.config.StateExtractorConfig;
import com.obsinity.service.core.support.CrdKeys;
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

    private final String baseLocation; // e.g. "classpath:/service-definitions/"

    public ResourceConfigSource(String baseLocation) {
        this.baseLocation = baseLocation.endsWith("/") ? baseLocation : baseLocation + "/";
    }

    public List<ServiceConfig> load() throws Exception {
        List<Resource> resources = locateResources();
        List<ServiceConfig> parsed = new ArrayList<>(resources.size());
        for (Resource r : resources) parsed.addAll(parseResource(r));
        return mergeByService(parsed);
    }

    private List<Resource> locateResources() throws Exception {
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
        return Stream.of(yml, yaml, json)
                .flatMap(Arrays::stream)
                .filter(r -> r.exists() && r.isReadable())
                .toList();
    }

    private List<ServiceConfig> parseResource(Resource r) throws Exception {
        String fname = safeName(r);
        log.info("Init-config: processing {}", fname);
        String text;
        try (InputStream in = r.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean looksLikeCrd = text.contains(CrdKeys.KEY_API_VERSION) && text.contains(CrdKeys.KEY_KIND);
        List<ServiceConfig> out = new ArrayList<>(1);
        if (looksLikeCrd) {
            List<ServiceConfig> crd = parseCrdDocuments(text, fname);
            if (!crd.isEmpty()) {
                log.info("Init-config: parsed {} CRD-derived ServiceConfig item(s) from {}", crd.size(), fname);
                out.addAll(crd);
                return out;
            }
        }
        try {
            List<ServiceConfig> scs = parseServiceConfigText(text, fname);
            if (!scs.isEmpty()) {
                log.info("Init-config: parsed {} ServiceConfig item(s) from {}", scs.size(), fname);
                out.addAll(scs);
            }
        } catch (Exception ex) {
            if (!looksLikeCrd) log.debug("Init-config: ServiceConfig parse failed for {} ({}).", fname, ex.toString());
        }
        if (out.isEmpty())
            log.warn("Init-config: no usable config parsed from {} (neither ServiceConfig nor CRD)", fname);
        return out;
    }

    private List<ServiceConfig> mergeByService(List<ServiceConfig> all) {
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
            Object api = root.get(CrdKeys.KEY_API_VERSION);
            Object kind = root.get(CrdKeys.KEY_KIND);
            if (api == null || kind == null) return List.of();

            String apiVersion = String.valueOf(api).trim().toLowerCase(Locale.ROOT);
            if (!apiVersion.equals(CrdKeys.API_VERSION_LOWER)) {
                log.warn("Unsupported apiVersion '{}' in {} — only obsinity/v1 is supported", apiVersion, fileNameHint);
                return List.of();
            }

            String kindStr = String.valueOf(kind).trim().toLowerCase(Locale.ROOT);
            if (CrdKeys.KIND_EVENT.equals(kindStr)) {
                ServiceConfig sc = fromEventCrd(root);
                return (sc == null) ? List.of() : List.of(sc);
            }
            if (CrdKeys.KIND_METRIC_COUNTER.equals(kindStr) || CrdKeys.KIND_METRIC_HISTOGRAM.equals(kindStr)) {
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
            Map<String, Object> meta = (Map<String, Object>) root.getOrDefault(CrdKeys.METADATA, Map.of());
            Map<String, Object> spec = (Map<String, Object>) root.getOrDefault(CrdKeys.SPEC, Map.of());
            Map<String, Object> schema = (Map<String, Object>) spec.getOrDefault(CrdKeys.SCHEMA, Map.of());

            String service = string(meta.get(CrdKeys.SERVICE));
            String eventName = string(meta.get(CrdKeys.NAME));
            if (service == null || eventName == null) return null;

            String category = resolveCategory(meta);
            String subCategory = resolveSubCategory(meta);

            if (declaresReservedFields(schema)) {
                log.warn(
                        "Event CRD '{}' declares reserved standard fields in schema.properties; remove them (eventId, timestamp, name, kind, trace, correlationId)",
                        eventName);
                return null;
            }

            EventIndexConfig attrIndex = new EventIndexConfig(UUID.randomUUID(), buildAttrIndexSpec(schema), null);
            String ttl = extractTtl(spec);
            EventConfig event =
                    new EventConfig(null, eventName, null, category, subCategory, List.of(), attrIndex, ttl);

            return ServiceConfig.of(service, "crd:" + eventName, List.of(event));
        } catch (Exception e) {
            log.debug("Failed to convert Event CRD to ServiceConfig: {}", e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String resolveCategory(Map<String, Object> meta) {
        Object cat = meta.get(CrdKeys.CATEGORY);
        if (cat == null) {
            Object labelsObj = meta.get(CrdKeys.LABELS);
            if (labelsObj instanceof Map<?, ?> labels) {
                Object c = labels.get(CrdKeys.CATEGORY);
                if (c != null) cat = c;
            }
        }
        return string(cat);
    }

    @SuppressWarnings("unchecked")
    private static String resolveSubCategory(Map<String, Object> meta) {
        Object subCat = meta.get(CrdKeys.SUB_CATEGORY);
        if (subCat == null) {
            Object labelsObj = meta.get(CrdKeys.LABELS);
            if (labelsObj instanceof Map<?, ?> labels) {
                Object sc = labels.get(CrdKeys.SUB_CATEGORY);
                if (sc != null) subCat = sc;
            }
        }
        return string(subCat);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAttrIndexSpec(Map<String, Object> schema) {
        List<String> paths = deriveIndexedPaths(schema);
        Map<String, Object> attrSpec = new LinkedHashMap<>();
        if (schema != null && !schema.isEmpty()) attrSpec.put("schema", schema);
        attrSpec.put("indexed", paths);
        return attrSpec;
    }

    @SuppressWarnings("unchecked")
    private boolean declaresReservedFields(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return false;
        Object propsObj = schema.get(CrdKeys.PROPERTIES);
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
        boolean nodeIndex = inheritedIndex || Boolean.TRUE.equals(node.get(CrdKeys.INDEX));
        String type = schemaType(node);
        if (CrdKeys.TYPE_OBJECT.equals(type)) {
            handleObjectNode(prefix, node, nodeIndex, out);
            return;
        }
        if (CrdKeys.TYPE_ARRAY.equals(type)) {
            handleArrayNode(prefix, node, nodeIndex, out);
            return;
        }
        // scalar leaf
        if (nodeIndex && !prefix.isEmpty()) out.add(prefix);
    }

    @SuppressWarnings("unchecked")
    private static void handleObjectNode(String prefix, Map<String, Object> node, boolean nodeIndex, List<String> out) {
        Map<String, Object> props = (Map<String, Object>) node.get(CrdKeys.PROPERTIES);
        if (props == null) return;
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Object val = e.getValue();
            if (!(val instanceof Map)) continue;
            Map<String, Object> child = (Map<String, Object>) val;
            String next = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            new ResourceConfigSource("").walkSchema(next, child, nodeIndex, out);
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleArrayNode(String prefix, Map<String, Object> node, boolean nodeIndex, List<String> out) {
        Object items = node.get(CrdKeys.ITEMS);
        if (items instanceof Map<?, ?> m) {
            String next = prefix + "[]";
            new ResourceConfigSource("").walkSchema(next, (Map<String, Object>) m, nodeIndex, out);
        }
    }

    private static String schemaType(Map<String, Object> node) {
        Object typeObj = node.get(CrdKeys.TYPE);
        return (typeObj == null) ? null : String.valueOf(typeObj);
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
        Map<String, com.obsinity.service.core.model.config.EventConfig> byKey = new LinkedHashMap<>();

        if (a.events() != null) {
            for (var e : a.events()) {
                String key = eventKey(e);
                if (!key.isBlank()) byKey.put(key, e);
            }
        }

        if (b.events() != null) {
            for (var e : b.events()) {
                String key = eventKey(e);
                if (key.isBlank()) continue;
                com.obsinity.service.core.model.config.EventConfig merged =
                        byKey.containsKey(key) ? mergeEvent(byKey.get(key), e) : e;
                byKey.put(key, merged);
            }
        }

        List<StateExtractorConfig> mergedExtractors = mergeStateExtractors(a.stateExtractors(), b.stateExtractors());
        List<RatioQueryConfig> mergedRatioQueries = mergeRatioQueries(a.ratioQueries(), b.ratioQueries());
        return ServiceConfig.of(
                a.service(), a.snapshotId(), new ArrayList<>(byKey.values()), mergedExtractors, mergedRatioQueries);
    }

    private static String eventKey(com.obsinity.service.core.model.config.EventConfig e) {
        String norm = e.eventNorm();
        if (norm != null && !norm.isBlank()) return norm;
        String name = e.eventName();
        return (name == null) ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static com.obsinity.service.core.model.config.EventConfig mergeEvent(
            com.obsinity.service.core.model.config.EventConfig base,
            com.obsinity.service.core.model.config.EventConfig incoming) {
        List<MetricConfig> mergedMetrics = mergeMetrics(base.metrics(), incoming.metrics());
        var attr = (incoming.attributeIndex() != null) ? incoming.attributeIndex() : base.attributeIndex();
        String cat = firstNonBlank(incoming.category(), base.category());
        String sub = firstNonBlank(incoming.subCategory(), base.subCategory());
        String ttl = firstNonBlank(incoming.retentionTtl(), base.retentionTtl());

        return new com.obsinity.service.core.model.config.EventConfig(
                base.uuid(), base.eventName(), base.eventNorm(), cat, sub, mergedMetrics, attr, ttl);
    }

    private static List<MetricConfig> mergeMetrics(List<MetricConfig> a, List<MetricConfig> b) {
        Map<String, MetricConfig> byName = new LinkedHashMap<>();
        if (a != null) {
            for (MetricConfig m : a) if (m != null && m.name() != null) byName.put(m.name(), m);
        }
        if (b != null) {
            for (MetricConfig m : b) if (m != null && m.name() != null) byName.put(m.name(), m);
        }
        return new ArrayList<>(byName.values());
    }

    private static List<StateExtractorConfig> mergeStateExtractors(
            List<StateExtractorConfig> first, List<StateExtractorConfig> second) {
        Map<String, StateExtractorConfig> byKey = new LinkedHashMap<>();
        if (first != null) {
            for (StateExtractorConfig cfg : first) {
                String key = extractorKey(cfg);
                if (!key.isEmpty()) {
                    byKey.put(key, cfg);
                }
            }
        }
        if (second != null) {
            for (StateExtractorConfig cfg : second) {
                String key = extractorKey(cfg);
                if (!key.isEmpty()) {
                    byKey.put(key, cfg);
                }
            }
        }
        return byKey.isEmpty() ? List.of() : new ArrayList<>(byKey.values());
    }

    private static String extractorKey(StateExtractorConfig cfg) {
        if (cfg == null) {
            return "";
        }
        String rawType = cfg.rawType() != null ? cfg.rawType().trim().toLowerCase(Locale.ROOT) : "";
        String objectType = cfg.objectType() != null ? cfg.objectType().trim().toLowerCase(Locale.ROOT) : "";
        String objectId =
                cfg.objectIdField() != null ? cfg.objectIdField().trim().toLowerCase(Locale.ROOT) : "";
        if (rawType.isEmpty() || objectType.isEmpty() || objectId.isEmpty()) {
            return "";
        }
        return rawType + "|" + objectType + "|" + objectId;
    }

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) return first;
        return fallback;
    }

    private static List<RatioQueryConfig> mergeRatioQueries(
            List<RatioQueryConfig> first, List<RatioQueryConfig> second) {
        Map<String, RatioQueryConfig> byName = new LinkedHashMap<>();
        if (first != null) {
            for (RatioQueryConfig query : first) {
                String key = ratioQueryKey(query);
                if (!key.isEmpty()) {
                    byName.put(key, query);
                }
            }
        }
        if (second != null) {
            for (RatioQueryConfig query : second) {
                String key = ratioQueryKey(query);
                if (!key.isEmpty()) {
                    byName.put(key, query);
                }
            }
        }
        return byName.isEmpty() ? List.of() : new ArrayList<>(byName.values());
    }

    private static String ratioQueryKey(RatioQueryConfig cfg) {
        if (cfg == null || cfg.name() == null || cfg.name().isBlank()) {
            return "";
        }
        return cfg.name().trim().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private ServiceConfig fromMetricCrd(Map<String, Object> root, String kindLower) {
        try {
            Map<String, Object> meta = (Map<String, Object>) root.getOrDefault(CrdKeys.METADATA, Map.of());
            Map<String, Object> spec = (Map<String, Object>) root.getOrDefault(CrdKeys.SPEC, Map.of());

            String service = string(meta.get(CrdKeys.SERVICE));
            String eventName = string(meta.get(CrdKeys.KIND_EVENT));
            String metricName = string(meta.get(CrdKeys.NAME));
            if (service == null || eventName == null || metricName == null) return null;

            String type = metricTypeFromKind(kindLower);
            List<String> dimensions = extractDimensions(spec);
            List<String> granularities = extractGranularities(spec);
            Map<String, Object> specJson = buildMetricSpecJson(spec, dimensions);
            String metricTtl = extractTtl(spec);

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
                    null,
                    metricTtl);

            com.obsinity.service.core.model.config.EventConfig ev =
                    new com.obsinity.service.core.model.config.EventConfig(
                            null, eventName, null, null, null, List.of(m), null, null);
            return ServiceConfig.of(service, "crd:" + metricName, List.of(ev));
        } catch (Exception e) {
            log.debug("Failed to convert Metric CRD to ServiceConfig: {}", e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String metricTypeFromKind(String kindLower) {
        return "metriccounter".equals(kindLower) ? "COUNTER" : "HISTOGRAM";
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractDimensions(Map<String, Object> spec) {
        Map<String, Object> key = (Map<String, Object>) spec.getOrDefault(CrdKeys.KEY, Map.of());
        List<String> dimensions = new ArrayList<>();
        Object dimsObj = key.get(CrdKeys.DIMENSIONS);
        if (dimsObj instanceof List<?> l) {
            for (Object d : l) {
                String s = string(d);
                if (s != null) dimensions.add(s);
            }
        }
        return dimensions;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractGranularities(Map<String, Object> spec) {
        Map<String, Object> rollup = (Map<String, Object>) spec.getOrDefault(CrdKeys.ROLLUP, Map.of());
        Map<String, Object> windowing = (Map<String, Object>) rollup.getOrDefault(CrdKeys.WINDOWING, Map.of());
        List<String> granularities = new ArrayList<>();
        Object grans = windowing.get(CrdKeys.GRANULARITIES);
        if (grans instanceof List<?> l) {
            for (Object g : l) {
                String s = string(g);
                if (s != null) granularities.add(s);
            }
        }
        return granularities;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildMetricSpecJson(Map<String, Object> spec, List<String> dimensions) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> rollup = (Map<String, Object>) spec.getOrDefault(CrdKeys.ROLLUP, Map.of());
        Map<String, Object> key = (Map<String, Object>) spec.getOrDefault(CrdKeys.KEY, Map.of());
        if (spec.containsKey(CrdKeys.VALUE)) out.put(CrdKeys.VALUE, spec.get(CrdKeys.VALUE));
        if (spec.containsKey(CrdKeys.BUCKETS)) out.put(CrdKeys.BUCKETS, spec.get(CrdKeys.BUCKETS));
        if (spec.containsKey(CrdKeys.FOLD)) out.put(CrdKeys.FOLD, spec.get(CrdKeys.FOLD));
        if (!rollup.isEmpty()) out.put(CrdKeys.ROLLUP, rollup);
        if (key.containsKey(CrdKeys.DIMENSIONS)) out.put(CrdKeys.KEY, Map.of(CrdKeys.DIMENSIONS, dimensions));
        if (spec.containsKey(CrdKeys.ATTRIBUTE_MAPPING))
            out.put(CrdKeys.ATTRIBUTE_MAPPING, spec.get(CrdKeys.ATTRIBUTE_MAPPING));
        if (spec.containsKey(CrdKeys.FILTERS)) out.put(CrdKeys.FILTERS, spec.get(CrdKeys.FILTERS));
        return out;
    }

    @SuppressWarnings("unchecked")
    private String extractTtl(Map<String, Object> spec) {
        if (spec == null || spec.isEmpty()) return null;
        try {
            Object ttlDirect = spec.get(CrdKeys.TTL);
            if (ttlDirect != null) {
                String s = string(ttlDirect);
                if (s != null && !s.isBlank()) return s;
            }
            Object retention = spec.get(CrdKeys.RETENTION);
            if (retention instanceof Map<?, ?> rmap) {
                Object ttl = rmap.get(CrdKeys.TTL);
                String s = string(ttl);
                if (s != null && !s.isBlank()) return s;
            }
        } catch (Exception ignore) {
            // fall through
        }
        return null;
    }
}

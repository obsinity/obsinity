package com.obsinity.controller.admin;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.obsinity.service.core.model.config.EventConfig;
import com.obsinity.service.core.model.config.EventIndexConfig;
import com.obsinity.service.core.model.config.MetricConfig;
import com.obsinity.service.core.model.config.ServiceConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Component;

/**
 * Parses a .tar.gz shaped like:
 *   services/<service>/events/<event>/event.yaml
 *   services/<service>/events/<event>/metrics/{counters,histograms,gauges}/*.yaml
 */
@Component
public class ServiceConfigArchiveLoader {

    private static final Pattern EVENT_YAML = Pattern.compile("^services/([^/]+)/events/([^/]+)/event\\.ya?ml$");
    private static final Pattern METRIC_YAML =
            Pattern.compile("^services/([^/]+)/events/([^/]+)/metrics/(counters|histograms|gauges)/([^/]+)\\.ya?ml$");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper canonicalMapper =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public ServiceConfig loadFromTarGz(byte[] tarGzBytes) {
        try (ByteArrayInputStream bin = new ByteArrayInputStream(tarGzBytes);
                GzipCompressorInputStream gzin = new GzipCompressorInputStream(bin);
                TarArchiveInputStream tin = new TarArchiveInputStream(gzin)) {

            // service -> eventNorm -> accumulator
            Map<String, Map<String, Acc>> services = new LinkedHashMap<>();

            TarArchiveEntry e;
            while ((e = tin.getNextTarEntry()) != null) {
                if (e.isDirectory()) continue;
                String path = normalize(e.getName());
                if (path.isBlank()) continue;

                Matcher em = EVENT_YAML.matcher(path);
                Matcher mm = METRIC_YAML.matcher(path);

                if (em.matches()) {
                    String service = em.group(1);
                    String eventFolder = em.group(2);
                    Map<String, Object> doc = readYamlMap(tin);

                    String eventName = eventNameFromDoc(doc).orElse(kebabToSnake(eventFolder));
                    String eventNorm = eventName.toLowerCase(Locale.ROOT);

                    Acc acc = accFor(services, service, eventNorm);
                    acc.eventName = eventName;
                    acc.eventDoc = doc; // used to derive attribute indexes
                } else if (mm.matches()) {
                    String service = mm.group(1);
                    String eventFolder = mm.group(2);
                    String kindDir = mm.group(3); // counters|histograms|gauges
                    String fileBase = mm.group(4); // without .yaml
                    Map<String, Object> doc = readYamlMap(tin);

                    String eventName = kebabToSnake(eventFolder);
                    String eventNorm = eventName.toLowerCase(Locale.ROOT);
                    Acc acc = accFor(services, service, eventNorm);

                    String metricType =
                            switch (kindDir) {
                                case "counters" -> "COUNTER";
                                case "histograms" -> "HISTOGRAM";
                                case "gauges" -> "GAUGE";
                                default -> "COUNTER";
                            };

                    String metricName = metadataName(doc).orElse(fileBase);

                    // keyed keys -> spec.key.dimensions (or key.dimensions)
                    List<String> keyed = listOfStrings(mapAt(doc, "spec", "key"), "dimensions");
                    if (keyed == null || keyed.isEmpty()) keyed = listOfStrings(mapAt(doc, "key"), "dimensions");

                    // rollups -> spec.aggregation.windowing.granularities (or aggregation.granularities)
                    List<String> rollups =
                            listOfStrings(mapAt(doc, "spec", "aggregation", "windowing"), "granularities");
                    if (rollups == null || rollups.isEmpty())
                        rollups = listOfStrings(mapAt(doc, "aggregation"), "granularities");

                    // filters -> spec.filters (or filters)
                    Map<String, Object> filters = mapAt(doc, "filters");
                    if (filters == null) filters = mapAt(doc, "spec", "filters");
                    if (filters == null) filters = Map.of();

                    // specJson (clean of control fields) – KEEP AS MAP
                    Map<String, Object> spec = mapAt(doc, "spec");
                    if (spec == null) spec = new LinkedHashMap<>();
                    spec.remove("state");
                    spec.remove("cutover_at");
                    spec.remove("grace_until");

                    // server-side stable hashes (use canonicalized JSON strings)
                    String specHash = shortHash(canonicalize(spec));

                    String bucketLayoutHash = null;
                    if ("HISTOGRAM".equals(metricType)) {
                        Map<String, Object> buckets = mapAt(spec, "buckets");
                        if (buckets != null && !buckets.isEmpty()) {
                            bucketLayoutHash = shortHash(canonicalize(buckets));
                        }
                    }

                    MetricConfig mc = new MetricConfig(
                            null, // uuid
                            metricName,
                            metricType, // type
                            spec, // specJson (MAP, not String)
                            specHash, // specHash (String)
                            keyed == null ? List.of() : keyed,
                            rollups == null ? List.of() : rollups,
                            filters, // filtersJson (Map)  <-- order per record
                            bucketLayoutHash, // bucketLayoutHash   <--
                            null, // backfillWindow
                            null, // cutoverAt
                            null, // graceUntil
                            "PENDING" // state
                            );
                    acc.metrics.add(mc);
                }
            }

            // Build ServiceConfig from accumulated structure.
            if (services.isEmpty()) {
                throw new IllegalArgumentException("Archive contains no recognizable service/event files");
            }
            if (services.size() != 1) {
                throw new IllegalArgumentException(
                        "Archive must contain exactly one service; found: " + services.keySet());
            }
            String service = services.keySet().iterator().next();
            List<EventConfig> evs = new ArrayList<>();
            for (Acc acc : services.get(service).values()) {
                EventIndexConfig idx = buildIndexConfig(acc.eventDoc);
                evs.add(new EventConfig(
                        null, // uuid
                        acc.eventName, // eventName
                        acc.eventName.toLowerCase(Locale.ROOT), // eventNorm
                        acc.metrics, // metrics
                        idx // attributeIndex
                        ));
            }

            // snapshot id (any deterministic/unique id you want)
            String snapshotId = shortHash(service + "|" + Instant.now());

            // Use EMPTY_DEFAULTS or customize (e.g., rollups/backfillWindow at service-level if you add them later)
            return new ServiceConfig(
                    service,
                    snapshotId,
                    Instant.now(),
                    ServiceConfig.EMPTY_DEFAULTS, // Defaults(List<String> rollups, String backfillWindow)
                    evs);

        } catch (IOException ex) {
            throw new RuntimeException("Failed to read config archive", ex);
        }
    }

    // ---------- helpers ----------
    private static String normalize(String p) {
        return p.replace('\\', '/');
    }

    private static String kebabToSnake(String s) {
        return s.replace('-', '_');
    }

    private Optional<String> eventNameFromDoc(Map<String, Object> doc) {
        Map<String, Object> meta = mapAt(doc, "metadata");
        if (meta != null) {
            Object n = meta.get("name");
            if (n instanceof String sn && !sn.isBlank()) return Optional.of(sn.trim());
        }
        return Optional.empty();
    }

    private String canonicalize(Object obj) {
        try {
            return canonicalMapper.writeValueAsString(obj == null ? Map.of() : obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String shortHash(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] full = md.digest(canonical.getBytes(UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", full[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> readYamlMap(InputStream in) throws IOException {
        return yaml.readValue(in, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> m, String... path) {
        Map<String, Object> cur = m;
        for (String p : path) {
            if (cur == null) return null;
            Object nxt = cur.get(p);
            if (!(nxt instanceof Map<?, ?>)) return null;
            cur = (Map<String, Object>) nxt;
        }
        return cur;
    }

    private static List<String> listOfStrings(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        if (!(v instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : list) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    private static String metadataString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof String s) ? s : null;
    }

    private static Optional<String> metadataName(Map<String, Object> doc) {
        Map<String, Object> meta = mapAt(doc, "metadata");
        if (meta == null) return Optional.empty();
        String n = metadataString(meta, "name");
        return (n == null || n.isBlank()) ? Optional.empty() : Optional.of(n.trim());
    }

    private EventIndexConfig buildIndexConfig(Map<String, Object> eventDoc) {
        Map<String, Object> spec = mapAt(eventDoc, "spec");
        Map<String, Object> schema = mapAt(spec, "schema");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indexed =
                schema == null ? List.of() : (List<Map<String, Object>>) schema.getOrDefault("indexed", List.of());

        Map<String, Object> specJson = new LinkedHashMap<>();
        specJson.put("indexed", indexed);

        String specHash = shortHash(canonicalize(specJson));
        return new EventIndexConfig(null, specJson, specHash);
    }

    private Acc accFor(Map<String, Map<String, Acc>> services, String service, String eventNorm) {
        return services.computeIfAbsent(service, s -> new LinkedHashMap<>()).computeIfAbsent(eventNorm, e -> new Acc());
    }

    private static final class Acc {
        String eventName; // original (snake) name
        Map<String, Object> eventDoc; // for attribute index extraction
        List<MetricConfig> metrics = new ArrayList<>();
    }
}

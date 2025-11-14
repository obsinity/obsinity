package com.obsinity.controller.admin;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.obsinity.service.core.model.config.EventConfig;
import com.obsinity.service.core.model.config.EventIndexConfig;
import com.obsinity.service.core.model.config.MetricConfig;
import com.obsinity.service.core.model.config.ServiceConfig;
import com.obsinity.service.core.support.Constants;
import com.obsinity.service.core.support.CrdKeys;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;

/**
 * Parses an archive shaped like:
 * services/<service>/events/<event>/event.yaml
 * services/<service>/events/<event>/metrics/{counters,histograms,gauges}/*.yaml
 * <p>
 * Accepts both .tar.gz and plain .tar (magic-byte sniffed).
 */
@Component
public class ServiceConfigArchiveLoader {

    private static final Pattern EVENT_YAML = Pattern.compile("^services/([^/]+)/events/([^/]+)/event\\.ya?ml$");
    private static final Pattern METRIC_YAML =
            Pattern.compile("^services/([^/]+)/events/([^/]+)/metrics/(counters|histograms|gauges)/([^/]+)\\.ya?ml$");

    // Local key constants to avoid duplicated string literals
    private static final String KEY_INDEXED = "indexed";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
    private final ObjectMapper canonicalMapper =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * Backward-compatible entry point; now accepts gzip OR plain tar.
     */
    public ServiceConfig loadFromTarGz(byte[] archiveBytes) {
        // Defensive: never operate directly on the original array; we may re-wrap it multiple times.
        Objects.requireNonNull(archiveBytes, "archiveBytes");

        try (InputStream in = layeredUngzip(new ByteArrayInputStream(archiveBytes))) {
            try (TarArchiveInputStream tin = new TarArchiveInputStream(in)) {

                // service -> eventNorm -> accumulator
                Map<String, Map<String, Acc>> services = new LinkedHashMap<>();

                TarArchiveEntry e;
                while ((e = tin.getNextTarEntry()) != null) {
                    String path = normalize(e.getName());
                    if (e.isDirectory() || path.isBlank()) continue;

                    Matcher em = EVENT_YAML.matcher(path);
                    Matcher mm = METRIC_YAML.matcher(path);

                    if (em.matches()) {
                        processEventYaml(tin, em, services);
                    } else if (mm.matches()) {
                        processMetricYaml(tin, mm, services);
                    }
                }

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
                    String ttl = retentionTtlFrom(acc.eventDoc);
                    evs.add(new EventConfig(
                            null,
                            acc.eventName,
                            acc.eventName.toLowerCase(Locale.ROOT),
                            categoryFrom(acc.eventDoc),
                            subCategoryFrom(acc.eventDoc),
                            acc.metrics,
                            idx,
                            ttl));
                }

                String snapshotId = shortHash(service + "|" + Instant.now());
                return new ServiceConfig(
                        service, snapshotId, Instant.now(), ServiceConfig.EMPTY_DEFAULTS, evs, List.of());
            }
        } catch (IOException io) {
            throw new RuntimeException("Failed to read config archive", io);
        }
    }

    private void processEventYaml(TarArchiveInputStream tin, Matcher em, Map<String, Map<String, Acc>> services)
            throws IOException {
        String service = em.group(1);
        String eventFolder = em.group(2);
        Map<String, Object> doc = readYamlMap(tin);
        String eventName = eventNameFromDoc(doc).orElse(kebabToSnake(eventFolder));
        String eventNorm = eventName.toLowerCase(Locale.ROOT);
        Acc acc = accFor(services, service, eventNorm);
        acc.eventName = eventName;
        acc.eventDoc = doc;
    }

    private void processMetricYaml(TarArchiveInputStream tin, Matcher mm, Map<String, Map<String, Acc>> services)
            throws IOException {
        String service = mm.group(1);
        String eventFolder = mm.group(2);
        String kindDir = mm.group(3);
        String fileBase = mm.group(4);
        Map<String, Object> doc = readYamlMap(tin);

        String eventName = kebabToSnake(eventFolder);
        String eventNorm = eventName.toLowerCase(Locale.ROOT);
        Acc acc = accFor(services, service, eventNorm);

        String metricType =
                switch (kindDir) {
                    case "counters" -> Constants.TYPE_COUNTER;
                    case "histograms" -> Constants.TYPE_HISTOGRAM;
                    case "gauges" -> Constants.TYPE_GAUGE;
                    default -> Constants.TYPE_COUNTER;
                };

        String metricName = metadataName(doc).orElse(fileBase);
        List<String> keyed = listOfStrings(mapAt(doc, CrdKeys.SPEC, CrdKeys.KEY), CrdKeys.DIMENSIONS);
        if (keyed == null || keyed.isEmpty()) keyed = listOfStrings(mapAt(doc, CrdKeys.KEY), CrdKeys.DIMENSIONS);

        List<String> rollups =
                listOfStrings(mapAt(doc, CrdKeys.SPEC, CrdKeys.ROLLUP, CrdKeys.WINDOWING), CrdKeys.GRANULARITIES);
        if (rollups == null || rollups.isEmpty())
            rollups = listOfStrings(mapAt(doc, CrdKeys.ROLLUP), CrdKeys.GRANULARITIES);

        Map<String, Object> filters = mapAt(doc, CrdKeys.FILTERS);
        if (filters == null) filters = mapAt(doc, CrdKeys.SPEC, CrdKeys.FILTERS);
        if (filters == null) filters = Map.of();

        Map<String, Object> spec = mapAt(doc, CrdKeys.SPEC);
        if (spec == null) spec = new LinkedHashMap<>();
        spec.remove(CrdKeys.STATE);
        spec.remove(CrdKeys.CUTOVER_AT);
        spec.remove(CrdKeys.GRACE_UNTIL);

        String specHash = shortHash(canonicalize(spec));

        String bucketLayoutHash = null;
        if (Constants.TYPE_HISTOGRAM.equals(metricType)) {
            Map<String, Object> buckets = mapAt(spec, CrdKeys.BUCKETS);
            if (buckets != null && !buckets.isEmpty()) {
                bucketLayoutHash = shortHash(canonicalize(buckets));
            }
        }

        String metricTtl = retentionTtlFrom(doc);
        MetricConfig mc = new MetricConfig(
                null,
                metricName,
                metricType,
                spec,
                specHash,
                keyed == null ? List.of() : keyed,
                rollups == null ? List.of() : rollups,
                filters,
                bucketLayoutHash,
                null,
                null,
                null,
                Constants.METRIC_STATE_PENDING,
                metricTtl);
        acc.metrics.add(mc);
    }

    /**
     * Return an InputStream ready to be read by TarArchiveInputStream:
     * - If bytes are gzipped (one or more layers), peel all gzip layers with JDK GZIPInputStream.
     * - Otherwise, return the original stream (plain .tar).
     */
    private static InputStream layeredUngzip(InputStream in) throws IOException {
        // We need mark/reset to peek magic without consuming. Wrap accordingly.
        BufferedInputStream bin = new BufferedInputStream(in);
        bin.mark(2);
        int b0 = bin.read();
        int b1 = bin.read();
        bin.reset();

        // GZIP magic 1F 8B?
        if (b0 == 0x1F && b1 == 0x8B) {
            // Peel gzip layers until it’s no longer gzip.
            InputStream current = bin;
            while (true) {
                // Wrap current in GZIPInputStream
                current = new java.util.zip.GZIPInputStream(current);
                // Peek next two bytes after this layer to check if another gzip layer follows.
                BufferedInputStream peek = new BufferedInputStream(current);
                peek.mark(2);
                int n0 = peek.read();
                int n1 = peek.read();
                peek.reset();
                if (!(n0 == 0x1F && n1 == 0x8B)) {
                    // Not another gzip; use this stream for TAR.
                    return peek;
                }
                // Another layer detected; continue peeling using the peek stream
                current = peek;
            }
        } else {
            // Not gzip; return as-is (plain TAR)
            return bin;
        }
    }

    // ---------- helpers ----------

    /**
     * GZIP magic (1F 8B).
     */
    private static boolean isGzip(byte[] bytes) {
        return bytes != null && bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    private static String normalize(String p) {
        return p.replace('\\', '/');
    }

    private static String kebabToSnake(String s) {
        return s.replace('-', '_');
    }

    private Optional<String> eventNameFromDoc(Map<String, Object> doc) {
        Map<String, Object> meta = mapAt(doc, CrdKeys.METADATA);
        if (meta != null) {
            Object n = meta.get(CrdKeys.NAME);
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

    private static String categoryFrom(Map<String, Object> doc) {
        Map<String, Object> meta = mapAt(doc, CrdKeys.METADATA);
        if (meta != null) {
            Object v = meta.get(CrdKeys.CATEGORY);
            if (v instanceof String s && !s.isBlank()) return s.trim();
            Map<String, Object> labels = mapAt(meta, CrdKeys.LABELS);
            if (labels != null) {
                Object sc = labels.get(CrdKeys.CATEGORY);
                if (sc instanceof String sx && !sx.isBlank()) return sx.trim();
            }
        }
        return null;
    }

    private static String subCategoryFrom(Map<String, Object> doc) {
        Map<String, Object> meta = mapAt(doc, CrdKeys.METADATA);
        if (meta != null) {
            Object v = meta.get(CrdKeys.SUB_CATEGORY);
            if (v instanceof String s && !s.isBlank()) return s.trim();
            Map<String, Object> labels = mapAt(meta, CrdKeys.LABELS);
            if (labels != null) {
                Object sc = labels.get(CrdKeys.SUB_CATEGORY);
                if (sc instanceof String sx && !sx.isBlank()) return sx.trim();
            }
        }
        return null;
    }

    private static String retentionTtlFrom(Map<String, Object> doc) {
        Map<String, Object> spec = mapAt(doc, CrdKeys.SPEC);
        if (spec == null) return null;
        Object ttlDirect = spec.get(CrdKeys.TTL);
        if (ttlDirect instanceof String s && !s.isBlank()) return s.trim();
        Map<String, Object> retention = mapAt(spec, CrdKeys.RETENTION);
        if (retention != null) {
            Object ttl = retention.get(CrdKeys.TTL);
            if (ttl instanceof String ts && !ts.isBlank()) return ts.trim();
        }
        return null;
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
        Map<String, Object> meta = mapAt(doc, CrdKeys.METADATA);
        if (meta == null) return Optional.empty();
        String n = metadataString(meta, CrdKeys.NAME);
        return (n == null || n.isBlank()) ? Optional.empty() : Optional.of(n.trim());
    }

    private EventIndexConfig buildIndexConfig(Map<String, Object> eventDoc) {
        Map<String, Object> spec = mapAt(eventDoc, CrdKeys.SPEC);
        Map<String, Object> schema = mapAt(spec, CrdKeys.SCHEMA);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indexed =
                schema == null ? List.of() : (List<Map<String, Object>>) schema.getOrDefault(KEY_INDEXED, List.of());

        Map<String, Object> specJson = new LinkedHashMap<>();
        specJson.put(KEY_INDEXED, indexed);

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

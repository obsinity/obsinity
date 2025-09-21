package com.obsinity.service.core.config;

import com.obsinity.service.core.model.config.EventConfig;
import com.obsinity.service.core.model.config.EventIndexConfig;
import com.obsinity.service.core.model.config.MetricConfig;
import com.obsinity.service.core.model.config.ServiceConfig;
import com.obsinity.service.core.model.config.ServiceConfigResponse;
import com.obsinity.service.core.repo.AttributeIndexRepository;
import com.obsinity.service.core.repo.EventRegistryRepository;
import com.obsinity.service.core.repo.MetricConfigRepository;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase-1 ingest: create/update only, no deletes.
 * Applies a full ServiceConfig transactionally:
 *  - ensures (service,event_norm) exists in event_registry_cfg
 *  - upserts metric_cfg rows (by (event_id, metric_key))
 *  - upserts attribute_index_cfg rows (idempotent by (event_id, spec_hash))
 */
@Service
public class ConfigIngestService {
    private static final Logger log = LoggerFactory.getLogger(ConfigIngestService.class);

    private final EventRegistryRepository eventRepo;
    private final MetricConfigRepository metricRepo;
    private final AttributeIndexRepository attrRepo;
    private final ServicesCatalogRepository servicesRepo;

    public ConfigIngestService(
            EventRegistryRepository eventRepo,
            MetricConfigRepository metricRepo,
            AttributeIndexRepository attrRepo,
            ServicesCatalogRepository servicesRepo) {
        this.eventRepo = eventRepo;
        this.metricRepo = metricRepo;
        this.attrRepo = attrRepo;
        this.servicesRepo = servicesRepo;
    }

    @Transactional
    public ServiceConfigResponse applyConfigUpdate(ServiceConfig req) {
        log.debug(
                "ConfigIngest: apply service={}, events={} (snapshotId={})",
                req.service(),
                req.events() == null ? 0 : req.events().size(),
                req.snapshotId());

        if (req.events() == null || req.events().isEmpty()) {
            return new ServiceConfigResponse(req.snapshotId(), true, 0, 0, 0);
        }

        final String service = trimToNull(req.service());
        if (service == null) throw new IllegalArgumentException("ServiceConfig.service must be provided");

        SvcIds svc = ensureService(service);
        Counts totals = processEvents(service, svc, req.events());

        ServiceConfigResponse resp =
                new ServiceConfigResponse(req.snapshotId(), true, totals.events, totals.metrics, totals.attrIdx);
        log.debug(
                "ConfigIngest: result events={}, metrics={}, attrIdx={}",
                totals.events,
                totals.metrics,
                totals.attrIdx);
        return resp;
    }

    private Counts processEvents(String service, SvcIds svc, List<EventConfig> events) {
        int ev = 0;
        int met = 0;
        int ai = 0;
        for (EventConfig e : events) {
            String eventName = trimToNull(e.eventName());
            if (eventName == null) throw new IllegalArgumentException("EventConfig.eventName must be provided");

            String norm = normalizeEventNorm(eventName, e.eventNorm());
            UUID eventId = ensureEvent(
                    svc.serviceId,
                    service,
                    svc.shortKey,
                    trimToNull(e.category()),
                    trimToNull(e.subCategory()),
                    eventName,
                    norm,
                    e.uuid(),
                    normalizeInterval(e.retentionTtl()));
            log.debug(
                    "ConfigIngest: ensured event service={}, name={}, norm={}, id={}",
                    service,
                    eventName,
                    norm,
                    eventId);
            ev++;

            met += upsertMetricsForEvent(service, norm, eventId, e.metrics());
            ai += upsertAttributeIndex(eventId, e.attributeIndex());
        }
        return new Counts(ev, met, ai);
    }

    private int upsertMetricsForEvent(String service, String norm, UUID eventId, List<MetricConfig> metrics) {
        if (metrics == null || metrics.isEmpty()) return 0;
        int count = 0;
        for (MetricConfig m : metrics) {
            String type = normalizeType(m.type());
            List<String> keyed = canonicalizeList(m.keyedKeys());
            List<String> rollups = canonicalizeList(m.rollups());

            Map<String, Object> cleanSpec = cleanSpecForHash(m.specJson());
            String specJson = toCanonicalJson(cleanSpec);
            String specHashSrv = shortHash(specJson);

            String bucketLayoutHash = trimToNull(m.bucketLayoutHash());
            if (com.obsinity.service.core.support.Constants.TYPE_HISTOGRAM.equals(type)) {
                Object buckets = (cleanSpec == null) ? null : cleanSpec.get("buckets");
                if (buckets instanceof Map<?, ?> bmap && !bmap.isEmpty()) {
                    bucketLayoutHash = shortHash(toCanonicalJson(castMap(buckets)));
                }
            }

            Map<String, Object> filtersMap =
                    Optional.ofNullable(m.filtersJson()).orElseGet(Map::of);
            String filtersJson = toCanonicalJson(filtersMap);

            String metricKey =
                    metricKey(service, norm, m.name(), type, keyed, rollups, filtersMap, bucketLayoutHash, cleanSpec);

            count += metricRepo.upsertMetric(
                    Optional.ofNullable(m.uuid()).orElse(UUID.randomUUID()),
                    eventId,
                    m.name(),
                    type,
                    specJson,
                    specHashSrv,
                    toPgTextArray(keyed),
                    toPgTextArray(rollups),
                    bucketLayoutHash,
                    filtersJson,
                    m.backfillWindow(),
                    m.cutoverAt(),
                    m.graceUntil(),
                    (m.state() == null ? com.obsinity.service.core.support.Constants.METRIC_STATE_PENDING : m.state()),
                    normalizeInterval(m.retentionTtl()),
                    metricKey);
        }
        return count;
    }

    private int upsertAttributeIndex(UUID eventId, EventIndexConfig ai) {
        if (ai == null) return 0;
        Map<String, Object> specMap = Optional.ofNullable(ai.specJson()).orElseGet(Map::of);
        String specJson = toCanonicalJson(specMap);
        String specHash = shortHash(specJson);
        return attrRepo.upsertAttributeIndex(
                Optional.ofNullable(ai.uuid()).orElse(UUID.randomUUID()), eventId, specJson, specHash);
    }

    private static String normalizeEventNorm(String eventName, String eventNorm) {
        String n = trimToNull(eventNorm);
        return (n != null) ? n.toLowerCase(Locale.ROOT).trim() : eventName.toLowerCase(Locale.ROOT);
    }

    private SvcIds ensureService(String service) {
        String shortKey = hex(sha256(service.getBytes(StandardCharsets.UTF_8)), 4);
        java.util.UUID serviceId = servicesRepo.findIdByServiceKey(service);
        if (serviceId == null) {
            try {
                servicesRepo.upsertService(service, shortKey, "Loaded via init-config");
            } catch (Exception ignore) {
            }
            serviceId = servicesRepo.findIdByServiceKey(service);
            if (serviceId == null) throw new IllegalStateException("Failed to resolve service id for " + service);
        }
        return new SvcIds(serviceId, shortKey);
    }

    private record Counts(int events, int metrics, int attrIdx) {}

    private record SvcIds(UUID serviceId, String shortKey) {}

    private UUID ensureEvent(
            UUID serviceId,
            String service,
            String serviceShort,
            String category,
            String subCategory,
            String eventName,
            String eventNorm,
            UUID desiredId,
            String retentionTtlInterval) {
        UUID idToUse = desiredId != null ? desiredId : UUID.randomUUID();
        // insertIfAbsent should be implemented with ON CONFLICT DO NOTHING to avoid races
        eventRepo.insertIfAbsent(
                idToUse,
                serviceId,
                service,
                serviceShort,
                category,
                subCategory,
                eventName,
                eventNorm,
                retentionTtlInterval);
        UUID existing = eventRepo.findIdByServiceIdAndEventNorm(serviceId, eventNorm);
        return existing != null ? existing : idToUse;
    }

    // ==== helpers ====

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizeType(String t) {
        String x = trimToNull(t);
        return (x == null) ? "COUNTER" : x.toUpperCase(Locale.ROOT);
    }

    private static List<String> canonicalizeList(List<String> in) {
        if (in == null || in.isEmpty()) return List.of();
        List<String> copy = new ArrayList<>(in.size());
        for (String s : in) if (s != null) copy.add(s.trim());
        copy.removeIf(String::isEmpty);
        // Sort lexicographically for deterministic metric_key and storage
        Collections.sort(copy);
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, Object> cleanSpecForHash(Map<String, Object> spec) {
        if (spec == null) return Map.of();
        Map<String, Object> cleaned = new LinkedHashMap<>(spec);
        cleaned.remove("state");
        cleaned.remove("cutover_at");
        cleaned.remove("grace_until");
        return cleaned;
    }

    /**
     * Normalize simple shorthand like "7d", "90d", "12h", "30m", "45s", "2w" into Postgres interval literals.
     * If input already looks like an interval (e.g., contains a space or full unit names), return as-is.
     */
    private static String normalizeInterval(String ttl) {
        if (ttl == null) return null;
        String s = ttl.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains(" ")
                || lower.contains("day")
                || lower.contains("hour")
                || lower.contains("min")
                || lower.contains("sec")
                || lower.contains("week")) {
            return s;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^(\\d+)([smhdw])$").matcher(lower);
        if (m.find()) {
            String num = m.group(1);
            String unit = m.group(2);
            String full =
                    switch (unit) {
                        case "s" -> "seconds";
                        case "m" -> "minutes";
                        case "h" -> "hours";
                        case "d" -> "days";
                        case "w" -> "weeks";
                        default -> null;
                    };
            if (full != null) return num + " " + full;
        }
        return s;
    }

    private static Map<String, Object> castMap(Object o) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        return m;
    }

    private static String shortHash(String canonicalJson) {
        byte[] sha = sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
        return hex(sha, 16);
    }

    private static String toCanonicalJson(Map<String, Object> map) {
        try {
            if (map == null) return "{}";
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON map", e);
        }
    }

    /** Convert a Java List<String> to a Postgres text[] literal like {"a","b"}. */
    private static String toPgTextArray(List<String> list) {
        if (list == null || list.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            String v = list.get(i);
            if (v == null) {
                sb.append("NULL");
            } else {
                sb.append('"')
                        .append(v.replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Build a stable metric_key from defining attributes.
     * Returns first 16 bytes of SHA-256 as 32 hex chars.
     */
    private static String metricKey(
            String service,
            String eventNorm,
            String metricName,
            String type,
            List<String> keyedKeys,
            List<String> rollups,
            Map<String, Object> filtersJson,
            String bucketLayoutHash,
            Map<String, Object> specJson) {
        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("service", service);
        norm.put("event_norm", eventNorm);
        norm.put("name", metricName);
        norm.put("type", type);
        norm.put("keyed_keys", keyedKeys == null ? List.of() : keyedKeys);
        norm.put("rollups", rollups == null ? List.of() : rollups);
        if (filtersJson != null && !filtersJson.isEmpty()) norm.put("filters", filtersJson);
        if (bucketLayoutHash != null) norm.put("bucket_layout_hash", bucketLayoutHash);

        if (specJson != null && !specJson.isEmpty()) {
            Map<String, Object> cleaned = new LinkedHashMap<>(specJson);
            cleaned.remove("state");
            cleaned.remove("cutover_at");
            cleaned.remove("grace_until");
            norm.put("spec", cleaned);
        }

        String canonical = toCanonicalJson(norm);
        byte[] sha = sha256(canonical.getBytes(StandardCharsets.UTF_8));
        return hex(sha, 16);
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hex(byte[] bytes, int takeBytes) {
        int n = Math.min(bytes.length, takeBytes);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", bytes[i]));
        return sb.toString();
    }
}

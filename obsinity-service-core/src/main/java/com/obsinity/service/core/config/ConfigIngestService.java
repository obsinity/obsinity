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
        int events = 0, metrics = 0, attrIdx = 0;

        if (req.events() == null || req.events().isEmpty()) {
            return new ServiceConfigResponse(req.snapshotId(), true, 0, 0, 0);
        }

        final String service = trimToNull(req.service());
        if (service == null) {
            throw new IllegalArgumentException("ServiceConfig.service must be provided");
        }

        // Ensure service exists in catalog (for partitioning and lookups)
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

        for (EventConfig eb : req.events()) {
            final String eventName = trimToNull(eb.eventName());
            if (eventName == null) {
                throw new IllegalArgumentException("EventConfig.eventName must be provided");
            }
            final String norm = trimToNull(eb.eventNorm()) != null
                    ? eb.eventNorm().toLowerCase(Locale.ROOT).trim()
                    : eventName.toLowerCase(Locale.ROOT);

            String category = trimToNull(eb.category());
            String subCategory = trimToNull(eb.subCategory());
            UUID eventId = ensureEvent(serviceId, service, shortKey, category, subCategory, eventName, norm, eb.uuid());
            log.debug(
                    "ConfigIngest: ensured event service={}, name={}, norm={}, id={}",
                    service,
                    eventName,
                    norm,
                    eventId);
            events++;

            // Metrics
            if (eb.metrics() != null) {
                for (MetricConfig m : eb.metrics()) {
                    // Normalize type and canonicalize keyedKeys/rollups ordering
                    final String type = normalizeType(m.type());

                    final List<String> keyedKeysCanonical = canonicalizeList(m.keyedKeys());
                    final List<String> rollupsCanonical = canonicalizeList(m.rollups());

                    // Canonical JSON for spec + recompute specHash server-side
                    final Map<String, Object> cleanSpec = cleanSpecForHash(m.specJson());
                    final String specJson = toCanonicalJson(cleanSpec);
                    final String specHashSrv = shortHash(specJson);

                    // Recompute bucketLayoutHash if histogram and buckets present
                    String bucketLayoutHash = trimToNull(m.bucketLayoutHash());
                    if ("HISTOGRAM".equals(type)) {
                        Object buckets = (cleanSpec == null) ? null : cleanSpec.get("buckets");
                        if (buckets instanceof Map<?, ?> bmap && !bmap.isEmpty()) {
                            bucketLayoutHash = shortHash(toCanonicalJson(castMap(buckets)));
                        }
                    }

                    // Filters canonical JSON (empty -> {})
                    final Map<String, Object> filtersMap =
                            Optional.ofNullable(m.filtersJson()).orElseGet(Map::of);
                    final String filtersJson = toCanonicalJson(filtersMap);

                    // Deterministic metric_key built from normalized pieces
                    final String metricKey = metricKey(
                            service,
                            norm,
                            m.name(),
                            type,
                            keyedKeysCanonical,
                            rollupsCanonical,
                            filtersMap,
                            bucketLayoutHash,
                            cleanSpec);

                    metrics += metricRepo.upsertMetric(
                            Optional.ofNullable(m.uuid()).orElse(UUID.randomUUID()),
                            eventId,
                            m.name(),
                            type,
                            specJson,
                            specHashSrv, // use server-side spec hash
                            toPgTextArray(keyedKeysCanonical),
                            toPgTextArray(rollupsCanonical),
                            bucketLayoutHash,
                            filtersJson,
                            m.backfillWindow(),
                            m.cutoverAt(),
                            m.graceUntil(),
                            (m.state() == null ? "PENDING" : m.state()),
                            metricKey);
                }
            }

            // Attribute index (optional)
            if (eb.attributeIndex() != null) {
                EventIndexConfig ai = eb.attributeIndex();
                Map<String, Object> specMap = Optional.ofNullable(ai.specJson()).orElseGet(Map::of);
                String specJson = toCanonicalJson(specMap);
                String specHash = shortHash(specJson); // recompute for safety
                attrIdx += attrRepo.upsertAttributeIndex(
                        Optional.ofNullable(ai.uuid()).orElse(UUID.randomUUID()), eventId, specJson, specHash);
            }
        }

        ServiceConfigResponse resp = new ServiceConfigResponse(req.snapshotId(), true, events, metrics, attrIdx);
        log.debug("ConfigIngest: result events={}, metrics={}, attrIdx={}", events, metrics, attrIdx);
        return resp;
    }

    private UUID ensureEvent(
            UUID serviceId,
            String service,
            String serviceShort,
            String category,
            String subCategory,
            String eventName,
            String eventNorm,
            UUID desiredId) {
        UUID idToUse = desiredId != null ? desiredId : UUID.randomUUID();
        // insertIfAbsent should be implemented with ON CONFLICT DO NOTHING to avoid races
        eventRepo.insertIfAbsent(
                idToUse, serviceId, service, serviceShort, category, subCategory, eventName, eventNorm);
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

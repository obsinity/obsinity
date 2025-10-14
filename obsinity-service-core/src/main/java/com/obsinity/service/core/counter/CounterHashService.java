package com.obsinity.service.core.counter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.stereotype.Service;

/**
 * Bi-directional hash cache for counter grouping keys.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CounterHashService {

    @Value("${obsinity.counters.hash.cache-size:50000}")
    private int cacheSize;

    @Value("${obsinity.counters.hash.ttl:PT5M}")
    @DurationUnit(ChronoUnit.MINUTES)
    private Duration ttl;

    @Getter
    private Cache<String, String> keyToHash;

    @Getter
    private Cache<String, String> hashToKey;

    private static final ObjectMapper CANONICAL_JSON =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @PostConstruct
    public void init() {
        keyToHash = Caffeine.newBuilder()
                .maximumSize(cacheSize)
                .expireAfterAccess(ttl)
                .expireAfterWrite(ttl)
                .recordStats()
                .build();

        hashToKey = Caffeine.newBuilder()
                .maximumSize(cacheSize)
                .expireAfterAccess(ttl)
                .expireAfterWrite(ttl)
                .recordStats()
                .build();

        log.info("Initialized counter hash caches size={} ttl={}.", cacheSize, ttl);
    }

    public String getOrCreateHash(Map<String, String> keyData) {
        String canonical = buildCanonicalKeyString(keyData);
        String hash = keyToHash.get(canonical, this::computeHash);
        hashToKey.put(hash, canonical);
        return hash;
    }

    public Map<String, String> getKeyDataForHash(String hash) {
        String canonical = hashToKey.getIfPresent(hash);
        if (canonical == null) {
            log.warn("Hash {} not found in reverse cache", hash);
            return Map.of();
        }
        keyToHash.put(canonical, hash);
        try {
            return CANONICAL_JSON.readValue(canonical, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse canonical JSON for hash {}", hash, e);
            return Map.of();
        }
    }

    private String buildCanonicalKeyString(Map<String, String> keyData) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        keyData.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> node.put(entry.getKey(), entry.getValue()));
        try {
            return CANONICAL_JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonically encode counter key", e);
        }
    }

    private String computeHash(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

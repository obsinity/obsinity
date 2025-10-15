package com.obsinity.service.core.counter;

import com.obsinity.service.core.config.CounterConfig;
import com.obsinity.service.core.config.EventTypeConfig;
import com.obsinity.service.core.model.EventEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CounterIngestService {

    private final CounterBuffer buffer;
    private final CounterHashService hashService;

    public void process(EventEnvelope envelope, EventTypeConfig eventConfig) {
        if (eventConfig.counters() == null || eventConfig.counters().isEmpty()) {
            return;
        }
        Map<String, Object> attributes = normalizeAttributes(envelope.getAttributes());
        Instant occurredAt = envelope.getTimestamp();
        UUID eventTypeId = eventConfig.eventId();

        for (CounterConfig counter : eventConfig.counters()) {
            Map<String, String> keyData = extractKeyData(counter.keyedKeys(), attributes);
            if (keyData.isEmpty()) {
                continue;
            }
            CounterGranularity granularity = counter.granularity();
            Instant aligned = granularity.baseBucket().align(occurredAt);
            long epoch = aligned.getEpochSecond();
            String keyHash = hashService.getOrCreateHash(keyData);
            buffer.increment(granularity, epoch, counter.id(), eventTypeId, keyHash, 1, keyData);
        }
    }

    private Map<String, String> extractKeyData(List<String> keyedKeys, Map<String, Object> attributes) {
        if (keyedKeys == null || keyedKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        boolean missing = false;
        for (String rawKey : keyedKeys) {
            if (rawKey == null || rawKey.isBlank()) {
                continue;
            }
            Object value = resolveAttribute(attributes, rawKey.trim());
            if (value == null) {
                log.debug("Missing counter attribute '{}'", rawKey);
                missing = true;
                break;
            }
            result.put(rawKey, stringify(value));
        }
        if (missing || result.isEmpty()) {
            return Map.of();
        }
        return result;
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return value.toString();
    }

    private Object resolveAttribute(Map<String, Object> attributes, String path) {
        if (attributes == null || path == null || path.isEmpty()) {
            return null;
        }
        if (!path.contains(".")) {
            return attributes.get(path);
        }
        String[] segments = path.split("\\.");
        Object current = attributes;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        attributes.forEach((key, value) -> mergeAttribute(normalized, key, value));
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private void mergeAttribute(Map<String, Object> target, String rawKey, Object value) {
        if (rawKey == null || rawKey.isBlank()) return;
        String key = rawKey.trim();

        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> child = target.containsKey(key) && target.get(key) instanceof Map<?, ?> existing
                    ? toMutableMap((Map<?, ?>) existing)
                    : new LinkedHashMap<>();
            mapValue.forEach((k, v) -> {
                if (k != null) mergeAttribute(child, k.toString(), v);
            });
            target.put(key, child);
            return;
        }

        if (key.contains(".")) {
            String[] segments = key.split("\\.");
            Map<String, Object> current = target;
            for (int i = 0; i < segments.length - 1; i++) {
                String segment = segments[i];
                Object next = current.get(segment);
                if (!(next instanceof Map<?, ?> nextMap)) {
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    current.put(segment, fresh);
                    current = fresh;
                } else {
                    Map<String, Object> mutable = toMutableMap(nextMap);
                    current.put(segment, mutable);
                    current = mutable;
                }
            }
            current.put(segments[segments.length - 1], value);
            return;
        }

        target.put(key, value);
    }

    private Map<String, Object> toMutableMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k != null ? k.toString() : null, v));
        return copy;
    }
}

package com.obsinity.service.core.index;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Indexes per-event attributes into the DATA table `event_attr_index`.
 *
 * Expected table shape (partitioned like events_raw: LIST(service_short) -> RANGE(occurred_at weekly)):
 *
 *   CREATE TABLE IF NOT EXISTS event_attr_index (
 *     service_short  TEXT        NOT NULL,
 *     occurred_at    TIMESTAMPTZ NOT NULL,
 *     service_id     UUID        NOT NULL,
 *     event_type_id  UUID        NOT NULL,
 *     event_id       UUID        NOT NULL,
 *     attr_name      TEXT        NOT NULL,
 *     attr_value     TEXT        NOT NULL,
 *     PRIMARY KEY (event_id, attr_name, attr_value)
 *   ) PARTITION BY LIST (service_short);
 *
 * This service:
 *  - fetches configured attribute paths for (serviceId,eventTypeId),
 *  - extracts values from the event attributes map,
 *  - writes one row per resolved value.
 *
 * Dependencies:
 *  - AttributeIndexSpecCfgRepository: returns attribute dot-paths to index for a given (serviceId,eventTypeId).
 */
@Service
public class AttributeIndexingService {

	private final NamedParameterJdbcTemplate jdbc;
	private final AttributeIndexSpecCfgRepository specRepo;

	public AttributeIndexingService(NamedParameterJdbcTemplate jdbc,
									AttributeIndexSpecCfgRepository specRepo) {
		this.jdbc = jdbc;
		this.specRepo = specRepo;
	}

	/**
	 * Index a single event's attributes according to config.
	 */
	@Transactional
	public void indexEvent(EventForIndex evt) throws DataAccessException {
		List<String> paths = specRepo.findIndexedAttributePaths(evt.serviceId(), evt.eventTypeId());
		if (paths == null || paths.isEmpty()) {
			return;
		}

		List<IndexRow> rows = new ArrayList<>(paths.size() * 2);
		for (String path : paths) {
			List<Object> values = extractValuesByPath(evt.attributes(), path);
			if (values.isEmpty()) continue;

			for (Object v : values) {
				String canon = normalizeToText(v);
				if (canon == null || canon.isBlank()) continue;

				rows.add(new IndexRow(
					evt.serviceShort(),
					evt.occurredAt(),
					evt.serviceId(),
					evt.eventTypeId(),
					evt.eventId(),
					path,
					canon
				));
			}
		}

		if (!rows.isEmpty()) {
			batchInsert(rows);
		}
	}

	/* ========================== Internal ========================== */

	private void batchInsert(List<IndexRow> rows) {
		final String sql = """
            INSERT INTO event_attr_index
              (service_short, occurred_at, service_id, event_type_id, event_id, attr_name, attr_value)
            VALUES
              (:service_short, :occurred_at, :service_id, :event_type_id, :event_id, :attr_name, :attr_value)
            ON CONFLICT DO NOTHING
            """;

		MapSqlParameterSource[] batch = rows.stream()
			.map(r -> new MapSqlParameterSource()
				.addValue("service_short", r.serviceShort)
				.addValue("occurred_at",   r.occurredAt)
				.addValue("service_id",    r.serviceId)
				.addValue("event_type_id", r.eventTypeId)
				.addValue("event_id",      r.eventId)
				.addValue("attr_name",     r.attrName)
				.addValue("attr_value",    r.attrValue)
			)
			.toArray(MapSqlParameterSource[]::new);

		jdbc.batchUpdate(sql, batch);
	}

	private record IndexRow(
		String        serviceShort,
		OffsetDateTime occurredAt,
		UUID          serviceId,
		UUID          eventTypeId,
		UUID          eventId,
		String        attrName,
		String        attrValue
	) {}

	/**
	 * Minimal event view needed by the indexer.
	 */
	public interface EventForIndex {
		String serviceShort();
		UUID serviceId();
		UUID eventTypeId();
		UUID eventId();
		OffsetDateTime occurredAt();
		Map<String, Object> attributes();
	}

	/**
	 * Extracts values from a nested Map using simple dot paths: a.b.c .
	 * If any step is a list, values are flattened (one row per element).
	 * Any final Map/List is stringified.
	 */
	@SuppressWarnings("unchecked")
	private static List<Object> extractValuesByPath(Map<String, Object> root, String dotPath) {
		if (root == null || dotPath == null || dotPath.isBlank()) return List.of();
		String[] parts = dotPath.split("\\.");
		List<Object> current = List.of(root);

		for (String part : parts) {
			List<Object> next = new ArrayList<>();
			for (Object node : current) {
				if (node instanceof Map<?, ?> m) {
					Object val = m.get(part);
					if (val == null) continue;
					if (val instanceof List<?> list) {
						next.addAll(list);
					} else {
						next.add(val);
					}
				} else if (node instanceof List<?> list) {
					for (Object item : list) {
						if (item instanceof Map<?, ?> mm) {
							Object val = mm.get(part);
							if (val == null) continue;
							if (val instanceof List<?> l2) next.addAll(l2);
							else next.add(val);
						}
					}
				}
			}
			current = next;
			if (current.isEmpty()) break;
		}

		return current.stream()
			.map(v -> (v instanceof Map || v instanceof List) ? stringify(v) : v)
			.collect(Collectors.toList());
	}

	private static String normalizeToText(Object v) {
		if (v == null) return null;
		String s = String.valueOf(v);
		// Hard cap to keep index rows predictable (adjust as needed)
		return s.length() <= 4096 ? s : s.substring(0, 4096);
	}

	private static String stringify(Object o) {
		if (o == null) return null;
		if (o instanceof Map<?, ?> m) {
			return m.entrySet().stream()
				.map(e -> e.getKey() + "=" + stringify(e.getValue()))
				.collect(Collectors.joining(",", "{", "}"));
		}
		if (o instanceof List<?> l) {
			return l.stream().map(AttributeIndexingService::stringify)
				.collect(Collectors.joining(",", "[", "]"));
		}
		return String.valueOf(o);
	}
}

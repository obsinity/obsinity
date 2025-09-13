package com.obsinity.service.core.objql;

import java.time.Instant;
import java.util.List;

/**
 * OB-JQL immutable query model (tiny AST).
 *
 * Example OB-JQL:
 *   service:"payments" event:"http_request"
 *   where attr.status_code =~ "5.."
 *   and   attr.latency_ms > 250
 *   since -1h
 *   order by occurred_at desc
 *   limit 200
 */
public record OBJql(
	String service,                 // required
	String event,                   // optional
	TimeRange time,                 // required or implied (since/ between)
	List<Predicate> predicates,     // zero or more
	Sort sort,                      // default by occurred_at desc
	Integer limit,                  // default 100
	List<String> selectFields       // optional projection; null/empty -> default set
) {
	public static record TimeRange(Instant start, Instant end) {}
	public static record Sort(String field, boolean asc) {}

	/** A predicate over envelope fields or attributes. */
	public sealed interface Predicate permits
		Eq, Ne, Gt, Ge, Lt, Le, Regex, Contains, NotContains
	{
		String lhs(); // e.g. "attr.status_code" or "service" or "trace_id"
		Object rhs();
	}

	public static record Eq(String lhs, Object rhs) implements Predicate {}
	public static record Ne(String lhs, Object rhs) implements Predicate {}
	public static record Gt(String lhs, Object rhs) implements Predicate {}
	public static record Ge(String lhs, Object rhs) implements Predicate {}
	public static record Lt(String lhs, Object rhs) implements Predicate {}
	public static record Le(String lhs, Object rhs) implements Predicate {}
	public static record Regex(String lhs, String rhs) implements Predicate {}
	public static record Contains(String lhs, Object rhs) implements Predicate {}
	public static record NotContains(String lhs, Object rhs) implements Predicate {}

	public static OBJql withDefaults(
		String service,
		String event,
		TimeRange time,
		List<Predicate> predicates,
		Sort sort,
		Integer limit,
		List<String> selectFields
	) {
		if (service == null || service.isBlank())
			throw new IllegalArgumentException("service is required");
		if (time == null)
			throw new IllegalArgumentException("time range is required (since/between)");
		if (limit == null || limit <= 0 || limit > 10_000) limit = 100; // cap safety
		if (sort == null) sort = new Sort("occurred_at", false);
		return new OBJql(service, event, time, List.copyOf(predicates), sort, limit,
			(selectFields == null || selectFields.isEmpty()) ? null : List.copyOf(selectFields));
	}
}

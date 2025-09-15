package com.obsinity.service.core.objql;

import java.util.*;

/**
 * Builds a native SQL with CTEs that:
 *
 *  1) filters base events by service/event/time/envelope predicates,
 *  2) resolves attribute matches from event_attr_index via INTERSECT (all predicates must match),
 *  3) pages with OFFSET/LIMIT in a stable order (occurred_at desc, event_id tiebreaker),
 *  4) returns rows from events_raw for the paged event_id set,
 *  5) also computes matched_count for UI pagination.
 *
 * Configure table names via constructor if different.
 */
public final class OBJqlCteBuilder {

    private final String eventsTable;
    private final String attrIndexTable;

    public OBJqlCteBuilder() {
        this("events_raw", "event_attr_index");
    }

    public OBJqlCteBuilder(String eventsTable, String attrIndexTable) {
        this.eventsTable = eventsTable;
        this.attrIndexTable = attrIndexTable;
    }

    public Built build(OBJql q, OBJqlPage page) {
        Map<String, Object> p = new LinkedHashMap<>();

        StringBuilder sql = new StringBuilder(2048);
        // Base filtered IDs (envelope fields only)
        sql.append("WITH base AS (\n")
                .append("  SELECT e.event_id, e.occurred_at\n")
                .append("  FROM ")
                .append(eventsTable)
                .append(" e\n")
                .append("  WHERE e.service_short = :svc\n");
        p.put("svc", q.service());

        if (q.event() != null && !q.event().isBlank()) {
            sql.append("    AND e.event_type = :evt\n");
            p.put("evt", q.event());
        }

        sql.append("    AND e.occurred_at >= :ts_start AND e.occurred_at < :ts_end\n");
        // Bind as SQL TIMESTAMP to avoid driver type inference issues for java.time.Instant
        p.put("ts_start", java.sql.Timestamp.from(q.time().start()));
        p.put("ts_end", java.sql.Timestamp.from(q.time().end()));

        // Envelope predicates (recognized set only)
        int pi = 0;
        for (OBJql.Predicate pred : q.predicates()) {
            String lhs = pred.lhs().toLowerCase(Locale.ROOT);
            if (isEnvelopeField(lhs)) {
                String name = "p" + (pi++);
                appendEnvelopePredicate(sql, p, name, pred);
            }
        }
        sql.append("),\n");

        // Attribute predicate subqueries as CTEs
        List<String> attrCtes = new ArrayList<>();
        int ai = 0;
        int attrCount = countAttrPreds(q);
        for (OBJql.Predicate pred : q.predicates()) {
            String lhs = pred.lhs().toLowerCase(Locale.ROOT);
            if (isEnvelopeField(lhs)) continue;

            String cte = "a" + (ai++);
            attrCtes.add(cte);
            String path = lhs.startsWith("attr.") ? lhs.substring("attr.".length()) : lhs;
            appendAttrCte(sql, p, cte, path, pred);
            if (ai < attrCount) sql.append(",\n");
        }
        if (attrCount == 0) {
            // no attr predicates, synthesize an "all" CTE from base
            sql.append("a0 AS (SELECT b.event_id FROM base b)\n");
            attrCtes.add("a0");
        } else {
            sql.append("\n");
        }

        // Combine predicate IDs via INTERSECT (all must match)
        sql.append(", matched AS (\n");
        for (int i = 0; i < attrCtes.size(); i++) {
            if (i == 0) {
                sql.append("  SELECT event_id FROM ").append(attrCtes.get(i)).append("\n");
            } else {
                sql.append("  INTERSECT SELECT event_id FROM ")
                        .append(attrCtes.get(i))
                        .append("\n");
            }
        }
        sql.append("),\n");

        // Stable ordering for paging
        String orderField = (q.sort() != null ? q.sort().field() : "occurred_at");
        boolean asc = (q.sort() != null && q.sort().asc());
        String order =
                switch (orderField.toLowerCase(Locale.ROOT)) {
                    case "occurred_at" -> "b.occurred_at " + (asc ? "asc" : "desc") + ", b.event_id";
                    case "received_at" -> "e.received_at " + (asc ? "asc" : "desc") + ", b.event_id";
                    default -> "b.occurred_at " + (asc ? "asc" : "desc") + ", b.event_id";
                };

        // Join back to base for ordering; compute total count; page
        sql.append("ordered AS (\n")
                .append("  SELECT b.event_id, b.occurred_at\n")
                .append("  FROM base b\n")
                .append("  JOIN matched m ON m.event_id = b.event_id\n")
                .append("  ORDER BY ")
                .append(order)
                .append("\n")
                .append("),\n")
                .append("page AS (\n")
                .append("  SELECT event_id FROM ordered OFFSET :off LIMIT :lim\n")
                .append("),\n")
                .append("counts AS (\n")
                .append("  SELECT (SELECT COUNT(*) FROM ordered) AS matched_count\n")
                .append(")\n");

        p.put("off", page.offset());
        p.put("lim", page.limit());

        // Final: return the page of events + total rows for UI paging
        sql.append("SELECT e.*,(SELECT matched_count FROM counts) AS matched_count\n")
                .append("FROM ")
                .append(eventsTable)
                .append(" e\n")
                .append("JOIN page pg ON pg.event_id = e.event_id\n")
                .append("ORDER BY ")
                .append(order.replace("b.", "e."))
                .append("\n");

        return new Built(sql.toString(), p);
    }

    // ------------ helpers ------------

    private int countAttrPreds(OBJql q) {
        int c = 0;
        for (OBJql.Predicate pred : q.predicates()) {
            if (pred.lhs().toLowerCase(Locale.ROOT).startsWith("attr.")) c++;
        }
        return c;
    }

    private void appendAttrCte(
            StringBuilder sql, Map<String, Object> p, String cte, String path, OBJql.Predicate pred) {
        // Build per-predicate CTE against the attribute index and restrict to base set via JOIN
        sql.append(cte).append(" AS (\n");
        sql.append("  SELECT x.event_id\n");
        sql.append("  FROM ").append(attrIndexTable).append(" x\n");
        sql.append("  JOIN base b ON b.event_id = x.event_id\n");
        sql.append("  WHERE x.attr_name = :").append(cte).append("_path\n");
        p.put(cte + "_path", path);

        String pname = cte + "_v";
        if (pred instanceof OBJql.Eq eq) {
            Object rhs = eq.rhs();
            sql.append("    AND x.attr_value = :").append(pname).append("\n");
            p.put(pname, rhs == null ? null : String.valueOf(rhs));
        } else if (pred instanceof OBJql.Ne ne) {
            Object rhs = ne.rhs();
            sql.append("    AND x.attr_value <> :").append(pname).append("\n");
            p.put(pname, rhs == null ? null : String.valueOf(rhs));
        } else if (pred instanceof OBJql.Regex re) {
            sql.append("    AND x.attr_value ~ :").append(pname).append("\n");
            p.put(pname, re.rhs());
        } else {
            throw new IllegalArgumentException("Only =, != and =~ are supported for attribute predicates at present");
        }
        sql.append(")\n");
    }

    private boolean isEnvelopeField(String lhs) {
        return switch (lhs) {
            case "service",
                    "event",
                    "event_id",
                    "trace_id",
                    "span_id",
                    "correlation_id",
                    "kind",
                    "occurred_at",
                    "received_at" -> true;
            default -> false;
        };
    }

    private void appendEnvelopePredicate(StringBuilder sql, Map<String, Object> p, String name, OBJql.Predicate pred) {
        String lhs = pred.lhs().toLowerCase(Locale.ROOT);
        String col =
                switch (lhs) {
                    case "service" -> "e.service_short";
                    case "event" -> "e.event_type";
                    case "event_id" -> "e.event_id";
                    case "trace_id" -> "e.trace_id";
                    case "span_id" -> "e.span_id";
                    case "correlation_id" -> "e.correlation_id";
                    case "kind" -> "e.kind";
                    case "occurred_at" -> "e.occurred_at";
                    case "received_at" -> "e.received_at";
                    default -> lhs; // custom column
                };

        if (pred instanceof OBJql.Eq eq) {
            sql.append("    AND ").append(col).append(" = :").append(name).append("\n");
            p.put(name, eq.rhs());
        } else if (pred instanceof OBJql.Ne ne) {
            sql.append("    AND ").append(col).append(" <> :").append(name).append("\n");
            p.put(name, ne.rhs());
        } else if (pred instanceof OBJql.Regex re) {
            sql.append("    AND ").append(col).append(" ~ :").append(name).append("\n");
            p.put(name, re.rhs());
        } else if (pred instanceof OBJql.Gt gt) {
            sql.append("    AND ").append(col).append(" > :").append(name).append("\n");
            p.put(name, gt.rhs());
        } else if (pred instanceof OBJql.Ge ge) {
            sql.append("    AND ").append(col).append(" >= :").append(name).append("\n");
            p.put(name, ge.rhs());
        } else if (pred instanceof OBJql.Lt lt) {
            sql.append("    AND ").append(col).append(" < :").append(name).append("\n");
            p.put(name, lt.rhs());
        } else if (pred instanceof OBJql.Le le) {
            sql.append("    AND ").append(col).append(" <= :").append(name).append("\n");
            p.put(name, le.rhs());
        } else {
            throw new IllegalArgumentException(
                    "Unsupported envelope predicate: " + pred.getClass().getSimpleName());
        }
    }

    private String toJsonLiteral(Object rhs) {
        if (rhs == null) return "null";
        if (rhs instanceof Boolean b) return b ? "true" : "false";
        if (rhs instanceof Number n) return n.toString();
        // string
        return "\"" + rhs.toString().replace("\"", "\\\"") + "\"";
    }

    public record Built(String sql, Map<String, Object> params) {}
}

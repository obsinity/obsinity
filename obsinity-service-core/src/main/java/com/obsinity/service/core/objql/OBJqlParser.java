package com.obsinity.service.core.objql;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pragmatic OB-JQL parser (no ANTLR) for operational queries.
 *
 * Supported:
 *  - service:"foo" | service=foo
 *  - event:"bar"   | event=bar
 *  - where CLAUSES joined by AND (and/or optional)
 *    attr.path = 123 | != | > | >= | < | <= | =~ "regex" | contains "x"
 *  - since -1h | -7d | between 2025-09-01T00:00:00Z .. 2025-09-02T00:00:00Z
 *  - order by occurred_at desc|asc
 *  - limit 100
 *  - select field1, field2, attr.x.y
 *
 * Time shorthands:
 *   now => current instant
 *   -<n>[s|m|h|d|w] relative to now
 */
public final class OBJqlParser {

    private static final Pattern KV =
            Pattern.compile("(service|event)\\s*[:=]\\s*(\"[^\"]+\"|\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT = Pattern.compile(
            "\\bselect\\s+(.+?)\\s+(where|since|between|order|limit|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WHERE =
            Pattern.compile("\\bwhere\\b(.+?)(since|between|order|limit|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ORDER =
            Pattern.compile("\\border\\s+by\\s+(\\S+)\\s*(asc|desc)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT = Pattern.compile("\\blimit\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BETWEEN =
            Pattern.compile("\\bbetween\\s+(\\S+)\\s*\\.\\.\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINCE = Pattern.compile("\\bsince\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    // Simple predicate tokens: lhs op rhs
    private static final Pattern PRED = Pattern.compile(
            "(?<lhs>[A-Za-z0-9_\\.]+)\\s*(?<op>=~|!=|=|>=|<=|>|<|contains|not\\s+contains)\\s*(?<rhs>\"[^\"]+\"|\\S+)",
            Pattern.CASE_INSENSITIVE);

    public OBJql parse(String q) {
        String src = Optional.ofNullable(q).orElse("").trim();
        if (src.isEmpty()) throw new IllegalArgumentException("empty OB-JQL");

        // service / event
        String service = null, event = null;
        Matcher kv = KV.matcher(src);
        while (kv.find()) {
            String k = kv.group(1).toLowerCase(Locale.ROOT);
            String v = unquote(kv.group(2));
            if (k.equals("service")) service = v;
            else if (k.equals("event")) event = v;
        }

        // select
        List<String> select = null;
        Matcher sel = SELECT.matcher(src + " ");
        if (sel.find()) {
            String block = sel.group(1).trim();
            select = Arrays.stream(block.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        // where predicates
        List<OBJql.Predicate> predicates = new ArrayList<>();
        Matcher wh = WHERE.matcher(src + " ");
        if (wh.find()) {
            String block = wh.group(1);
            // naive split by AND/OR; default AND; treat OR later if needed
            String[] toks = block.split("(?i)\\s+and\\s+");
            for (String t : toks) {
                Matcher pm = PRED.matcher(t.trim());
                if (pm.find()) {
                    predicates.add(toPredicate(pm.group("lhs"), pm.group("op"), unquote(pm.group("rhs"))));
                }
            }
        }

        // time
        OBJql.TimeRange time = null;
        Matcher btw = BETWEEN.matcher(src);
        if (btw.find()) {
            Instant s = parseTime(btw.group(1));
            Instant e = parseTime(btw.group(2));
            time = new OBJql.TimeRange(s, e);
        }
        if (time == null) {
            Matcher si = SINCE.matcher(src);
            if (si.find()) {
                Instant end = Instant.now();
                Instant start = parseRelative(si.group(1), end);
                time = new OBJql.TimeRange(start, end);
            }
        }
        if (time == null) {
            // default: last 24h to now
            Instant end = Instant.now();
            time = new OBJql.TimeRange(end.minusSeconds(24 * 3600), end);
        }

        // order + limit
        OBJql.Sort sort = null;
        Matcher om = ORDER.matcher(src);
        if (om.find()) {
            sort = new OBJql.Sort(om.group(1), "asc".equalsIgnoreCase(om.group(2)));
        }
        Integer limit = null;
        Matcher lm = LIMIT.matcher(src);
        if (lm.find()) limit = Integer.parseInt(lm.group(1));

        return OBJql.withDefaults(service, event, time, predicates, sort, limit, select);
    }

    // --- helpers ---

    private String unquote(String s) {
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private OBJql.Predicate toPredicate(String lhs, String op, String rhs) {
        String o = op.toLowerCase(Locale.ROOT).trim();
        return switch (o) {
            case "=" -> new OBJql.Eq(lhs, parseScalar(rhs));
            case "!=" -> new OBJql.Ne(lhs, parseScalar(rhs));
            case ">" -> new OBJql.Gt(lhs, parseNumber(rhs));
            case ">=" -> new OBJql.Ge(lhs, parseNumber(rhs));
            case "<" -> new OBJql.Lt(lhs, parseNumber(rhs));
            case "<=" -> new OBJql.Le(lhs, parseNumber(rhs));
            case "=~" -> new OBJql.Regex(lhs, rhs);
            case "contains" -> new OBJql.Contains(lhs, parseScalar(rhs));
            case "not contains" -> new OBJql.NotContains(lhs, parseScalar(rhs));
            default -> throw new IllegalArgumentException("Unsupported operator: " + op);
        };
    }

    private Object parseScalar(String v) {
        // try number
        try {
            return Long.valueOf(v);
        } catch (Exception ignore) {
        }
        try {
            return Double.valueOf(v);
        } catch (Exception ignore) {
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) return Boolean.valueOf(v);
        return v; // string
    }

    private Number parseNumber(String v) {
        try {
            return Long.valueOf(v);
        } catch (Exception ignore) {
        }
        return Double.valueOf(v);
    }

    private Instant parseTime(String t) {
        if (t.equalsIgnoreCase("now")) return Instant.now();
        if (t.startsWith("-")) return parseRelative(t, Instant.now());
        // ISO-ish
        return OffsetDateTime.parse(t).toInstant();
    }

    private Instant parseRelative(String r, Instant base) {
        // -5m, -2h, -7d, -1w
        String s = r.trim();
        if (!s.startsWith("-")) throw new IllegalArgumentException("Relative time must start with '-'");
        char unit = s.charAt(s.length() - 1);
        long val = Long.parseLong(s.substring(1, s.length() - 1));
        long seconds =
                switch (unit) {
                    case 's' -> val;
                    case 'm' -> val * 60;
                    case 'h' -> val * 3600;
                    case 'd' -> val * 86400;
                    case 'w' -> val * 7 * 86400;
                    default -> throw new IllegalArgumentException("Unsupported relative unit: " + unit);
                };
        return base.minusSeconds(seconds);
    }
}

package com.obsinity.controller.rest;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.api.FrictionlessData;
import com.obsinity.service.core.api.ResponseFormat;
import com.obsinity.service.core.objql.OBJql;
import com.obsinity.service.core.objql.OBJql.AttrExpr;
import com.obsinity.service.core.objql.OBJqlPage;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import com.obsinity.service.core.search.SearchService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final SearchService search;
    private final ObjectMapper mapper;
    private final ServicesCatalogRepository servicesRepo;
    private final String embeddedKey;
    private final String linksKey;
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    public SearchController(
            SearchService search,
            ObjectMapper mapper,
            ServicesCatalogRepository servicesRepo,
            @Value("${obsinity.api.hal.embedded:embedded}") String embeddedKey,
            @Value("${obsinity.api.hal.links:links}") String linksKey) {
        this.search = search;
        this.mapper = mapper;
        this.servicesRepo = servicesRepo;
        this.embeddedKey = embeddedKey;
        this.linksKey = linksKey;
    }

    /**
     * JSON Search endpoint for events.
     * Body structure examples are documented in obsinity-reference-service/insomnia.yaml
     */
    @PostMapping(
            value = "/events",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> search(@RequestBody SearchBody body) {
        validate(body);

        OBJqlPage page = OBJqlPage.of(body.offset, body.limit);

        // Build OB-JQL AST from JSON (match -> attribute predicates)
        OBJql ast = toOBJql(body);

        if (log.isInfoEnabled()) {
            String periodDesc = body.period != null
                    ? (body.period.previous != null
                            ? ("previous=" + body.period.previous)
                            : (body.period.between != null ? ("between=" + body.period.between) : ""))
                    : "";
            log.info(
                    "POST /api/search/events service={}, event={}, {}, limit={}, offset={}",
                    body.service,
                    body.event,
                    periodDesc,
                    page.limit(),
                    page.offset());
        }

        // Execute CTE-backed search (returns full events from events_raw)
        List<Map<String, Object>> rows = search.query(ast, page);

        // Post-filter full events using SQL-like ops on JSON paths
        if (body.filter != null) {
            List<FilterClause> filter = normalizeFilter(body.filter);
            rows = rows.stream()
                    .filter(row -> evalFilter(filter, hydrateEnvelope(row, body)))
                    .toList();
        }

        // Normalize row output: ensure attributes is a plain JSON object, not a driver wrapper
        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            data.add(normalizeRow(row));
        }

        long total = extractTotal(rows, ast, page);
        long count = data.size();
        int limit = page.limit();
        long offset = page.offset();
        ResponseFormat format = ResponseFormat.defaulted(body.format);

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("count", count);
        wrapper.put("total", total);
        wrapper.put("limit", limit);
        wrapper.put("offset", offset);
        wrapper.put("format", format.wireValue());
        Map<String, Object> embedded = new LinkedHashMap<>();
        Object payload = format == ResponseFormat.COLUMNAR ? FrictionlessData.columnar(data, mapper) : data;
        embedded.put("events", payload);
        wrapper.put(embeddedKey, embedded);
        wrapper.put(linksKey, buildLinks(body, offset, limit, count, total, format));
        return wrapper;
    }

    // -------------------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------------------

    public static class SearchBody {
        public String service; // required
        public String event; // optional
        public Period period; // required (since or between)
        public Object match; // single {attribute,op,value} or {and:[...]} or {or:[...]} or [ ... ]
        public Object filter; // same shape as match but uses {path,op,value}
        public List<Order> order; // optional
        public Integer limit; // defaulted by service
        public Long offset; // defaulted by service
        public ResponseFormat format; // row (default) | columnar
    }

    public static class Period {
        @JsonAlias("since")
        public String previous; // e.g. -1h, -24h (formerly 'since')

        public List<String> between; // [isoStart, isoEnd]
    }

    public static class Order {
        public String field; // startedAt | receivedAt (column names may still be snake_case)
        public String dir; // asc|desc
    }

    public static class MatchClause {
        public String attribute; // indexed path (e.g., api.name, http.status)
        public String op; // =, !=, like, ilike
        public Object value;
    }

    public static class FilterClause {
        public String path; // e.g., trace.correlationId, attributes.api.name, event.subCategory
        public String op; // =, !=, like, ilike
        public Object value;
        public List<FilterClause> and; // nested
        public List<FilterClause> or; // nested
    }

    // -------------------------------------------------------------------------------------
    // Validation and mapping
    // -------------------------------------------------------------------------------------

    private void validate(SearchBody b) {
        if (b == null) throw new IllegalArgumentException("Body is required");
        if (b.service == null || b.service.isBlank()) throw new IllegalArgumentException("'service' is required");
        if (b.period == null
                || (b.period.previous == null && (b.period.between == null || b.period.between.size() != 2)))
            throw new IllegalArgumentException("'period' must have 'previous' or 'between' [start,end]");
    }

    private OBJql toOBJql(SearchBody b) {
        // Resolve service partition key from catalog when given full service key
        String svc = b.service;
        if (svc != null && !svc.isBlank()) {
            String partitionKey = servicesRepo.findPartitionKeyByServiceKey(svc);
            if (partitionKey != null && !partitionKey.isBlank()) svc = partitionKey;
        }

        // Time range
        Instant end = Instant.now();
        Instant start;
        if (b.period.previous != null && !b.period.previous.isBlank()) {
            start = parseRelative(b.period.previous, end);
        } else {
            start = parseInstant(b.period.between.get(0));
            end = parseInstant(b.period.between.get(1));
        }
        OBJql.TimeRange tr = new OBJql.TimeRange(start, end);

        // Predicates from match: build boolean expression over attribute index
        List<OBJql.Predicate> preds = new ArrayList<>(); // keep for envelope if needed
        AttrExpr attrExpr = null;
        if (b.match != null) {
            attrExpr = buildAttrExpr(b.match);
        }

        // Order
        OBJql.Sort sort = null;
        if (b.order != null && !b.order.isEmpty()) {
            Order o = b.order.get(0);
            boolean asc = o != null && "asc".equalsIgnoreCase(String.valueOf(o.dir));
            String field = (o != null && o.field != null) ? o.field : "started_at";
            sort = new OBJql.Sort(field, asc);
        }

        Integer limit = (b.limit != null && b.limit > 0) ? b.limit : null;

        return OBJql.withDefaults(svc, b.event, tr, preds, sort, limit, null, attrExpr);
    }

    // -------------------------------------------------------------------------------------
    // Filter evaluation (post-processing over full event row)
    // -------------------------------------------------------------------------------------

    private Map<String, Object> hydrateEnvelope(Map<String, Object> row, SearchBody body) {
        Map<String, Object> env = new LinkedHashMap<>();

        // trace.*
        Map<String, Object> trace = new LinkedHashMap<>();
        // Prefer camelCase keys in output; values read from DB columns
        putIfPresent(trace, "correlationId", row.get("correlation_id"));
        putIfPresent(trace, "traceId", row.get("trace_id"));
        putIfPresent(trace, "spanId", row.get("span_id"));
        env.put("trace", trace);

        // event.* (limited)
        Map<String, Object> event = new LinkedHashMap<>();
        putIfPresent(event, "name", row.get("event_type"));
        putIfPresent(event, "kind", row.get("kind"));
        env.put("event", event);

        // attributes JSONB -> Map
        Object attrs = row.get("attributes");
        Map<String, Object> attributes = parseAttributes(attrs);
        env.put("attributes", attributes);

        // envelope roots for convenience
        env.put("startedAt", row.get("started_at"));
        env.put("receivedAt", row.get("received_at"));
        env.put("service", row.get("service_partition_key"));
        env.put("eventType", row.get("event_type"));

        return env;
    }

    private Map<String, Object> parseAttributes(Object v) {
        if (v == null) return Map.of();
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mm = (Map<String, Object>) m;
            return mm;
        }
        Map<String, Object> parsed;
        try {
            parsed = mapper.readValue(String.valueOf(v), MAP_TYPE);
        } catch (Exception e) {
            parsed = Map.of();
        }
        return parsed;
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> n = new LinkedHashMap<>(row.size());
        n.putAll(row);
        // attributes -> parsed Map
        n.put("attributes", parseAttributes(row.get("attributes")));
        // drop matched_count from rows; rollup is exposed at wrapper.total
        n.remove("matched_count");
        return n;
    }

    private long extractTotal(List<Map<String, Object>> rows, OBJql ast, OBJqlPage page) {
        if (rows != null && !rows.isEmpty()) {
            Object mc = rows.get(0).get("matched_count");
            if (mc instanceof Number num) return num.longValue();
            try {
                return Long.parseLong(String.valueOf(mc));
            } catch (Exception ignore) {
            }
        }
        // If this page returned no rows, attempt to fetch first page to read matched_count, else 0
        try {
            List<Map<String, Object>> first = search.query(ast, OBJqlPage.of(0L, 1));
            if (first != null && !first.isEmpty()) {
                Object mc = first.get(0).get("matched_count");
                if (mc instanceof Number num) return num.longValue();
                return Long.parseLong(String.valueOf(mc));
            }
        } catch (Exception ignore) {
        }
        return 0L;
    }

    private Map<String, Object> buildLinks(
            SearchBody body, long offset, int limit, long count, long total, ResponseFormat format) {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", linkFor(offset, limit, body, format));
        links.put("first", linkFor(0, limit, body, format));
        long lastOffset = (total <= 0) ? 0 : Math.max(0, ((total - 1) / (long) limit) * (long) limit);
        links.put("last", linkFor(lastOffset, limit, body, format));
        long prevOffset = Math.max(0, offset - (long) limit);
        if (offset > 0) links.put("prev", linkFor(prevOffset, limit, body, format));
        long nextOffset = offset + (long) limit;
        // Be robust if total is unknown (0): include next when page is full
        if (total > 0) {
            if (offset + count < total) links.put("next", linkFor(nextOffset, limit, body, format));
        } else if (count >= limit) {
            links.put("next", linkFor(nextOffset, limit, body, format));
        }
        return links;
    }

    private Map<String, Object> linkFor(long off, int lim, SearchBody body, ResponseFormat format) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("href", "/api/search/events");
        link.put("method", "POST");
        // Reuse the original body but with updated paging
        Map<String, Object> b = mapper.convertValue(body, new TypeReference<Map<String, Object>>() {});
        b.put("offset", off);
        b.put("limit", lim);
        if (format != null) {
            b.put("format", format.wireValue());
        }
        link.put("body", b);
        return link;
    }

    private boolean evalFilter(List<FilterClause> clauses, Map<String, Object> env) {
        for (FilterClause fc : clauses) {
            if (!evalClause(fc, env)) return false;
        }
        return true;
    }

    private boolean evalClause(FilterClause fc, Map<String, Object> env) {
        if (fc.and != null && !fc.and.isEmpty()) {
            for (FilterClause c : fc.and) if (!evalClause(c, env)) return false;
            return true;
        }
        if (fc.or != null && !fc.or.isEmpty()) {
            for (FilterClause c : fc.or) if (evalClause(c, env)) return true;
            return false;
        }
        String op = fc.op == null ? "=" : fc.op.toLowerCase(Locale.ROOT);
        Object actual = readPath(env, fc.path);
        Object expected = fc.value;
        if (op.equals("=")) return Objects.equals(stringify(actual), stringify(expected));
        if (op.equals("!=")) return !Objects.equals(stringify(actual), stringify(expected));
        if (op.equals("like")) return like(stringify(actual), String.valueOf(expected), false);
        if (op.equals("ilike")) return like(stringify(actual), String.valueOf(expected), true);
        throw new IllegalArgumentException("Unsupported filter op: " + fc.op);
    }

    private Object readPath(Map<String, Object> root, String path) {
        if (path == null || path.isBlank()) return null;
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String p : parts) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(p);
            if (cur == null) return null;
        }
        return cur;
    }

    private String stringify(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        return String.valueOf(v);
    }

    // -------------------------------------------------------------------------------------
    // Normalization helpers
    // -------------------------------------------------------------------------------------

    // Build attribute boolean expression from JSON shape
    private AttrExpr buildAttrExpr(Object node) {
        if (node instanceof Map<?, ?> mm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) mm;
            if (map.containsKey("and")) {
                return new AttrExpr.And(buildList(map.get("and")));
            }
            if (map.containsKey("or")) {
                return new AttrExpr.Or(buildList(map.get("or")));
            }
            // leaf
            MatchClause mc = mapper.convertValue(map, MatchClause.class);
            return new AttrExpr.Leaf(toPredicate(mc));
        } else if (node instanceof List<?> arr) {
            // implicit AND for arrays
            return new AttrExpr.And(buildList(arr));
        }
        // assume leaf
        MatchClause mc = mapper.convertValue(node, MatchClause.class);
        return new AttrExpr.Leaf(toPredicate(mc));
    }

    private List<AttrExpr> buildList(Object arr) {
        List<AttrExpr> out = new ArrayList<>();
        if (arr instanceof List<?> list) {
            for (Object o : list) out.add(buildAttrExpr(o));
        } else {
            out.add(buildAttrExpr(arr));
        }
        return out;
    }

    private OBJql.Predicate toPredicate(MatchClause mc) {
        if (mc == null || mc.attribute == null || mc.op == null) {
            throw new IllegalArgumentException("Invalid match leaf: attribute/op required");
        }
        String lhs = "attr." + mc.attribute;
        String op = mc.op.toLowerCase(Locale.ROOT);
        return switch (op) {
            case "=" -> new OBJql.Eq(lhs, mc.value);
            case "!" -> new OBJql.Ne(lhs, mc.value);
            case "!=" -> new OBJql.Ne(lhs, mc.value);
            case "like" -> new OBJql.Like(lhs, String.valueOf(mc.value));
            case "ilike" -> new OBJql.ILike(lhs, String.valueOf(mc.value));
            default -> throw new IllegalArgumentException("Unsupported match op: " + mc.op);
        };
    }

    private List<FilterClause> normalizeFilter(Object f) {
        if (f == null) return List.of();
        if (f instanceof Map<?, ?> mm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) mm;
            if (map.containsKey("and")) return List.of(group("and", map.get("and")));
            if (map.containsKey("or")) return List.of(group("or", map.get("or")));
            return List.of(mapper.convertValue(map, FilterClause.class));
        } else if (f instanceof List<?> l) {
            List<FilterClause> items = new ArrayList<>();
            for (Object o : l) items.add(mapper.convertValue(o, FilterClause.class));
            return items;
        }
        return List.of();
    }

    private FilterClause group(String op, Object items) {
        FilterClause g = new FilterClause();
        List<FilterClause> kids = new ArrayList<>();
        if (items instanceof List<?> l) {
            for (Object o : l) kids.add(mapper.convertValue(o, FilterClause.class));
        } else {
            kids.add(mapper.convertValue(items, FilterClause.class));
        }
        if ("and".equals(op)) g.and = kids;
        else g.or = kids;
        return g;
    }

    // -------------------------------------------------------------------------------------
    // Time and pattern helpers
    // -------------------------------------------------------------------------------------

    private Instant parseInstant(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    private Instant parseRelative(String r, Instant base) {
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

    private boolean like(String actual, String pattern, boolean ci) {
        if (actual == null) return false;
        return likeMatch(actual, pattern, ci);
    }

    private boolean likeMatch(String s, String p, boolean ci) {
        if (s == null || p == null) return false;
        if (ci) {
            s = s.toLowerCase(Locale.ROOT);
            p = p.toLowerCase(Locale.ROOT);
        }
        int i = 0, j = 0;
        int starIdx = -1, match = 0;
        while (i < s.length()) {
            if (j < p.length() && (p.charAt(j) == '_' || p.charAt(j) == s.charAt(i))) {
                i++;
                j++;
            } else if (j < p.length() && p.charAt(j) == '%') {
                starIdx = j;
                match = i;
                j++;
            } else if (starIdx != -1) {
                j = starIdx + 1;
                match++;
                i = match;
            } else {
                return false;
            }
        }
        while (j < p.length() && p.charAt(j) == '%') j++;
        return j == p.length();
    }

    private void putIfPresent(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }
}

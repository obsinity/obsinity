package com.obsinity.service.core.search;

import com.obsinity.service.core.objql.OBJql;
import com.obsinity.service.core.objql.OBJqlCteBuilder;
import com.obsinity.service.core.objql.OBJqlPage;
import com.obsinity.service.core.objql.OBJqlParser;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * JDBC implementation over events_raw + attribute index (CTE-based).
 * Safe parameterization; logs SQL at debug level.
 */
@Service
public class JdbcSearchService implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(JdbcSearchService.class);
    private static final String EXPLAIN_PREFIX = "EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT) ";

    private final NamedParameterJdbcTemplate jdbc;
    private final OBJqlParser parser = new OBJqlParser();
    private final OBJqlCteBuilder builder;

    @Value("${obsinity.search.explain.enabled:false}")
    private boolean explainEnabled;

    public JdbcSearchService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // Adjust table names here if needed:
        this.builder = new OBJqlCteBuilder("events_raw", "event_attr_index");
    }

    @Override
    public List<Map<String, Object>> query(String objql, OBJqlPage page, boolean includeTotal) {
        OBJql ast = parser.parse(objql);
        return query(ast, page, includeTotal);
    }

    @Override
    public List<Map<String, Object>> query(OBJql ast, OBJqlPage page, boolean includeTotal) {
        var built = buildQuery(ast, page, includeTotal);
        if (log.isDebugEnabled()) {
            log.debug("OB-JQL SQL:\n{}\nparams: {}", built.sql(), built.params());
        }
        if (explainEnabled && log.isInfoEnabled()) {
            logExplain(ast, page, built);
        }
        return jdbc.queryForList(built.sql(), built.params());
    }

    private OBJqlCteBuilder.Built buildQuery(OBJql ast, OBJqlPage page, boolean includeTotal) {
        if (isDirectPageQueryEligible(ast)) {
            return buildDirectPageQuery(ast, page, includeTotal);
        }
        return builder.build(ast, page, includeTotal);
    }

    private boolean isDirectPageQueryEligible(OBJql ast) {
        if (ast == null || ast.time() == null) return false;
        if (ast.attrExpr() != null) return false;
        if (ast.event() == null || ast.event().isBlank()) return false;
        if (ast.predicates() != null && !ast.predicates().isEmpty()) return false;
        String field = ast.sort() == null ? "started_at" : ast.sort().field();
        return normalizeSortField(field).equals("started_at");
    }

    private OBJqlCteBuilder.Built buildDirectPageQuery(OBJql ast, OBJqlPage page, boolean includeTotal) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("svc", ast.service());
        params.put("evt", ast.event());
        params.put("ts_start", Timestamp.from(ast.time().start()));
        params.put("ts_end", Timestamp.from(ast.time().end()));
        params.put("off", page.offset());
        params.put("lim", page.limit());

        boolean asc = ast.sort() != null && ast.sort().asc();
        String direction = asc ? "asc" : "desc";

        StringBuilder sql = new StringBuilder(512);
        if (includeTotal) {
            sql.append("WITH counts AS MATERIALIZED (\n")
                    .append("  SELECT COUNT(*) AS matched_count\n")
                    .append("  FROM events_raw e\n")
                    .append("  WHERE e.service_partition_key = :svc\n")
                    .append("    AND e.event_type = :evt\n")
                    .append("    AND e.started_at >= :ts_start AND e.started_at < :ts_end\n")
                    .append(")\n");
        }
        sql.append("SELECT e.*");
        if (includeTotal) {
            sql.append(",(SELECT matched_count FROM counts) AS matched_count");
        }
        sql.append("\nFROM events_raw e\n")
                .append("WHERE e.service_partition_key = :svc\n")
                .append("  AND e.event_type = :evt\n")
                .append("  AND e.started_at >= :ts_start AND e.started_at < :ts_end\n")
                .append("ORDER BY e.started_at ")
                .append(direction)
                .append(", e.event_id\n")
                .append("OFFSET :off LIMIT :lim\n");
        return new OBJqlCteBuilder.Built(sql.toString(), params);
    }

    private String normalizeSortField(String field) {
        if (field == null || field.isBlank()) return "started_at";
        String normalized = field.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "startedat", "started_at" -> "started_at";
            case "receivedat", "received_at" -> "received_at";
            default -> normalized;
        };
    }

    private void logExplain(OBJql ast, OBJqlPage page, OBJqlCteBuilder.Built built) {
        long startedNs = System.nanoTime();
        try {
            List<String> planLines =
                    jdbc.query(EXPLAIN_PREFIX + built.sql(), built.params(), (rs, rowNum) -> rs.getString(1));
            long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
            String plan = planLines.stream().collect(Collectors.joining("\n"));
            log.info(
                    "OB-JQL EXPLAIN ANALYZE service={}, event={}, start={}, end={}, limit={}, offset={}, explainMs={}\nSQL:\n{}\nparams={}\nplan:\n{}",
                    ast.service(),
                    ast.event(),
                    ast.time().start(),
                    ast.time().end(),
                    page.limit(),
                    page.offset(),
                    elapsedMs,
                    built.sql(),
                    built.params(),
                    plan);
        } catch (Exception ex) {
            log.warn(
                    "Failed to run OB-JQL EXPLAIN ANALYZE service={}, event={}, start={}, end={}",
                    ast.service(),
                    ast.event(),
                    ast.time().start(),
                    ast.time().end(),
                    ex);
        }
    }
}

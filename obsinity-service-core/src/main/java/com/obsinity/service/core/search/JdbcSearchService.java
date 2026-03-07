package com.obsinity.service.core.search;

import com.obsinity.service.core.objql.OBJql;
import com.obsinity.service.core.objql.OBJqlCteBuilder;
import com.obsinity.service.core.objql.OBJqlPage;
import com.obsinity.service.core.objql.OBJqlParser;
import java.util.List;
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
    public List<Map<String, Object>> query(String objql) {
        return query(objql, OBJqlPage.firstPage());
    }

    @Override
    public List<Map<String, Object>> query(OBJql ast) {
        return query(ast, OBJqlPage.firstPage());
    }

    @Override
    public List<Map<String, Object>> query(String objql, OBJqlPage page) {
        OBJql ast = parser.parse(objql);
        return query(ast, page);
    }

    @Override
    public List<Map<String, Object>> query(OBJql ast, OBJqlPage page) {
        var built = builder.build(ast, page);
        if (log.isDebugEnabled()) {
            log.debug("OB-JQL (CTE) SQL:\n{}\nparams: {}", built.sql(), built.params());
        }
        if (explainEnabled && log.isInfoEnabled()) {
            logExplain(ast, page, built);
        }
        return jdbc.queryForList(built.sql(), built.params());
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

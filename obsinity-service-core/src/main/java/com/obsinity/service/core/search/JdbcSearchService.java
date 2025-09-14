package com.obsinity.service.core.search;

import com.obsinity.service.core.objql.OBJql;
import com.obsinity.service.core.objql.OBJqlCteBuilder;
import com.obsinity.service.core.objql.OBJqlPage;
import com.obsinity.service.core.objql.OBJqlParser;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * JDBC implementation over events_raw + attribute index (CTE-based).
 * Safe parameterization; logs SQL at debug level.
 */
@Service
public class JdbcSearchService implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(JdbcSearchService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final OBJqlParser parser = new OBJqlParser();
    private final OBJqlCteBuilder builder;

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
        return jdbc.queryForList(built.sql(), built.params());
    }
}

package com.obsinity.service.core.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.obsinity.service.core.objql.OBJql;
import com.obsinity.service.core.objql.OBJqlPage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcSearchServiceTest {

    @Test
    void usesDirectPageQueryForSimpleEventSearch() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of("matched_count", 10L)));

        JdbcSearchService service = new JdbcSearchService(jdbc);
        OBJql ast = OBJql.withDefaults(
                "svc",
                "evt",
                new OBJql.TimeRange(Instant.parse("2026-04-07T13:00:00Z"), Instant.parse("2026-04-07T14:00:00Z")),
                List.of(),
                new OBJql.Sort("started_at", false),
                1000,
                null,
                null);

        service.query(ast, OBJqlPage.of(1000L, 1000));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(jdbc).queryForList(sql.capture(), anyMap());
        assertThat(sql.getValue()).contains("WITH counts AS MATERIALIZED");
        assertThat(sql.getValue()).doesNotContain("WITH base AS MATERIALIZED");
        assertThat(sql.getValue()).contains("ORDER BY e.started_at desc, e.event_id");
    }

    @Test
    void fallsBackToCteQueryWhenAttributeExpressionIsPresent() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of("matched_count", 10L)));

        JdbcSearchService service = new JdbcSearchService(jdbc);
        OBJql ast = OBJql.withDefaults(
                "svc",
                "evt",
                new OBJql.TimeRange(Instant.parse("2026-04-07T13:00:00Z"), Instant.parse("2026-04-07T14:00:00Z")),
                List.of(),
                new OBJql.Sort("started_at", false),
                1000,
                null,
                new OBJql.AttrExpr.Leaf(new OBJql.Eq("attr.status", "ACTIVE")));

        service.query(ast, OBJqlPage.of(0L, 1000));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(jdbc).queryForList(sql.capture(), anyMap());
        assertThat(sql.getValue()).contains("WITH base AS MATERIALIZED");
        assertThat(sql.getValue()).contains("matched AS MATERIALIZED");
    }
}

package com.obsinity.service.storage.impl;

import com.obsinity.service.core.catalog.EventCatalogService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcEventCatalogService implements EventCatalogService {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEventCatalogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensureType(String type, String description) {
        jdbc.update(
                "insert into event_type_catalog(type, description) values(:t,:d) on conflict (type) do nothing",
                new MapSqlParameterSource().addValue("t", type).addValue("d", description == null ? "" : description));
    }

    @Override
    public void addIndexPath(String type, String path) {
        jdbc.update(
                "insert into event_index_catalog(type, path) values(:t,:p) on conflict do nothing",
                new MapSqlParameterSource().addValue("t", type).addValue("p", path));
    }

    @Override
    public List<Map<String, Object>> listTypes() {
        return jdbc.queryForList(
                "select type, description, created_at from event_type_catalog order by type",
                new MapSqlParameterSource());
    }
}

package com.obsinity.service.core.repo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttributeValuesRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AttributeValuesRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String serviceShort, String attrName, String prefix, long offset, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT attr_value, seen_count, first_seen, last_seen "
                        + "FROM attribute_distinct_values "
                        + "WHERE service_short = :svc AND attr_name = :attr ")
                .append(prefix != null && !prefix.isEmpty() ? "AND attr_value ILIKE :pref " : "")
                .append("ORDER BY seen_count DESC, attr_value ASC ")
                .append("OFFSET :off LIMIT :lim");

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("svc", serviceShort)
                .addValue("attr", attrName)
                .addValue("off", offset)
                .addValue("lim", limit);
        if (prefix != null && !prefix.isEmpty()) {
            p.addValue("pref", prefix + "%");
        }
        return jdbc.queryForList(sql.toString(), p);
    }

    public long count(String serviceShort, String attrName, String prefix) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM attribute_distinct_values "
                        + "WHERE service_short = :svc AND attr_name = :attr ")
                .append(prefix != null && !prefix.isEmpty() ? "AND attr_value ILIKE :pref" : "");

        Map<String, Object> p = new HashMap<>();
        p.put("svc", serviceShort);
        p.put("attr", attrName);
        if (prefix != null && !prefix.isEmpty()) {
            p.put("pref", prefix + "%");
        }
        Long n = jdbc.queryForObject(sql.toString(), p, Long.class);
        return n == null ? 0L : n;
    }

    public List<Map<String, Object>> listNames(String serviceShort, String prefix, long offset, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT attr_name, COUNT(*) AS value_count "
                        + "FROM attribute_distinct_values "
                        + "WHERE service_short = :svc ")
                .append(prefix != null && !prefix.isEmpty() ? "AND attr_name ILIKE :pref " : "")
                .append("GROUP BY attr_name ORDER BY attr_name ASC ")
                .append("OFFSET :off LIMIT :lim");

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("svc", serviceShort)
                .addValue("off", offset)
                .addValue("lim", limit);
        if (prefix != null && !prefix.isEmpty()) p.addValue("pref", prefix + "%");
        return jdbc.queryForList(sql.toString(), p);
    }

    public long countNames(String serviceShort, String prefix) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(DISTINCT attr_name) FROM attribute_distinct_values " + "WHERE service_short = :svc ")
                .append(prefix != null && !prefix.isEmpty() ? "AND attr_name ILIKE :pref" : "");
        Map<String, Object> p = new HashMap<>();
        p.put("svc", serviceShort);
        if (prefix != null && !prefix.isEmpty()) p.put("pref", prefix + "%");
        Long n = jdbc.queryForObject(sql.toString(), p, Long.class);
        return n == null ? 0L : n;
    }
}

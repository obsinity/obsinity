package com.obsinity.service.core.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ServicesCatalogRepository {

    private final JdbcTemplate jdbc;

    public ServicesCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int upsertService(String serviceKey, String shortKey, String description) {
        return jdbc.update(
                """
                INSERT INTO service_registry (service_key, short_key, description)
                VALUES (?, ?, ?)
                ON CONFLICT (service_key) DO NOTHING
                """,
                serviceKey,
                shortKey,
                description);
    }

    public java.util.UUID findIdByServiceKey(String serviceKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM service_registry WHERE service_key = ?",
                    (rs, rowNum) -> (java.util.UUID) rs.getObject(1),
                    serviceKey);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** Returns the 8-char short_key for a given service_key, or null if unknown. */
    public String findShortKeyByServiceKey(String serviceKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT short_key FROM service_registry WHERE service_key = ?", String.class, serviceKey);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }
}

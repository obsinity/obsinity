package com.obsinity.service.core.index;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads attribute index paths from attribute_index_cfg joined to event_registry_cfg.
 */
@Repository
public class JdbcAttributeIndexSpecCfgRepository implements AttributeIndexSpecCfgRepository {

    private final JdbcTemplate jdbc;

    public JdbcAttributeIndexSpecCfgRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> findIndexedAttributePaths(UUID serviceId, UUID eventTypeId) {
        // Extract indexed paths from spec_json for the given event id
        return jdbc.queryForList(
                """
                select jsonb_array_elements_text(spec_json->'indexed') as attr_path
                from attribute_index_registry
                where event_id = ?
                """,
                String.class,
                eventTypeId);
    }
}

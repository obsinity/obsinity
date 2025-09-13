package com.obsinity.service.core.index.impl;

import com.obsinity.service.core.index.AttributeIndexSpecCfgRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class JdbcAttributeIndexSpecCfgRepository implements AttributeIndexSpecCfgRepository {

	private final JdbcTemplate jdbc;

	public JdbcAttributeIndexSpecCfgRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<String> findIndexedAttributePaths(UUID serviceId, UUID eventTypeId) {
		// Adjust table/columns to your actual config schema
		return jdbc.queryForList(
			"""
			SELECT attr_path
			FROM attribute_index_cfg
			WHERE service_id = ? AND event_type_id = ? AND enabled = true
			""",
			String.class, serviceId, eventTypeId
		);
	}
}

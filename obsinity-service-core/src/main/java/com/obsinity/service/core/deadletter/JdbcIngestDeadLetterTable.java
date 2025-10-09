package com.obsinity.service.core.deadletter;

import java.sql.Types;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcIngestDeadLetterTable implements IngestDeadLetterTable {

    private static final Logger log = LoggerFactory.getLogger(JdbcIngestDeadLetterTable.class);
    private static final String INSERT_SQL =
            """
        insert into event_ingest_dead_letters(
              id, source, reason, error, payload
        ) values (
              :id, :source, :reason, :error, :payload
        )
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIngestDeadLetterTable(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String payload, String reason, String detail, String source) {
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("source", source)
                    .addValue("reason", reason)
                    .addValue("error", detail)
                    .addValue("payload", payload, Types.VARCHAR);
            jdbc.update(INSERT_SQL, params);
        } catch (DataAccessException ex) {
            log.error("Failed to persist ingest dead letter due to {}", reason, ex);
        }
    }
}

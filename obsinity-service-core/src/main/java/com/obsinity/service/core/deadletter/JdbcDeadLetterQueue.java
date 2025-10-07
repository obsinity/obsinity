package com.obsinity.service.core.deadletter;

import com.obsinity.service.core.impl.JsonUtil;
import com.obsinity.service.core.model.EventEnvelope;
import java.sql.Types;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcDeadLetterQueue implements DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(JdbcDeadLetterQueue.class);
    private static final String INSERT_SQL =
            """
        insert into event_dead_letters(
              id, service_key, event_type, event_id, reason, error, payload
        ) values (
              :id, :service_key, :event_type, :event_id, :reason, :error, cast(:payload as jsonb)
        )
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDeadLetterQueue(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void publish(EventEnvelope envelope, String reason, String detail) {
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("service_key", envelope.getServiceId())
                    .addValue("event_type", envelope.getName())
                    .addValue("event_id", envelope.getEventId())
                    .addValue("reason", reason)
                    .addValue("error", detail)
                    .addValue("payload", JsonUtil.toJson(envelope), Types.VARCHAR);
            jdbc.update(INSERT_SQL, params);
        } catch (DataAccessException ex) {
            log.error("Failed to persist event into dead-letter queue: {}", reason, ex);
        }
    }
}

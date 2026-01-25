package com.obsinity.service.core.state.transition.counter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.state.transition.codec.StateCodec;
import com.obsinity.service.core.state.transition.inference.TransitionInferenceCandidate;
import com.obsinity.service.core.state.transition.inference.TransitionInferenceCandidateRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTransitionCounterSnapshotRepository
        implements TransitionCounterSnapshotStore, TransitionInferenceCandidateRepository {
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StateCodec codec;

    public JdbcTransitionCounterSnapshotRepository(
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, StateCodec codec) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.codec = codec;
    }

    @Override
    public TransitionCounterSnapshot find(UUID serviceId, String objectType, String objectId, String attribute) {
        if (serviceId == null || objectType == null || objectId == null || attribute == null) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("object_id", objectId)
                .addValue("attribute", attribute);
        return jdbc
                .query(
                        """
                select last_state, seen_states, seen_states_bits, last_event_ts, terminal_state
                from obsinity.object_transition_snapshots
                where service_id = :service_id
                  and object_type = :object_type
                  and object_id = :object_id
                  and attribute = :attribute
                """,
                        params,
                        (rs, rowNum) -> {
                            String lastState = rs.getString("last_state");
                            String seenJson = rs.getString("seen_states");
                            Instant lastEventTs = rs.getTimestamp("last_event_ts") != null
                                    ? rs.getTimestamp("last_event_ts").toInstant()
                                    : null;
                            String terminalState = rs.getString("terminal_state");
                            byte[] seenBits = rs.getBytes("seen_states_bits");
                            SeenStates seenStates =
                                    decodeSeenStates(serviceId, objectType, attribute, seenBits, seenJson);
                            return new TransitionCounterSnapshot(lastState, seenStates, lastEventTs, terminalState);
                        })
                .stream()
                .findFirst()
                .orElse(null);
    }

    @Override
    public void upsert(
            UUID serviceId,
            String objectType,
            String objectId,
            String attribute,
            String lastState,
            SeenStates seenStates,
            Instant lastEventTs,
            String terminalState) {
        if (serviceId == null || objectType == null || objectId == null || attribute == null) {
            return;
        }
        String seenJson = encodeSeenStates(seenStates, codec);
        byte[] seenBits = encodeSeenStatesBits(seenStates);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("object_id", objectId)
                .addValue("attribute", attribute)
                .addValue("last_state", lastState)
                .addValue("seen_states", seenJson)
                .addValue("seen_states_bits", seenBits)
                .addValue("last_event_ts", lastEventTs != null ? java.sql.Timestamp.from(lastEventTs) : null)
                .addValue("terminal_state", terminalState);

        jdbc.update(
                """
            insert into obsinity.object_transition_snapshots(
                service_id,
                object_type,
                object_id,
                attribute,
                last_state,
                seen_states,
                seen_states_bits,
                last_event_ts,
                terminal_state)
            values (:service_id, :object_type, :object_id, :attribute, :last_state, :seen_states::jsonb,
                    :seen_states_bits, :last_event_ts, :terminal_state)
            on conflict (service_id, object_type, object_id, attribute)
            do update set last_state = excluded.last_state,
                          seen_states = excluded.seen_states,
                          seen_states_bits = excluded.seen_states_bits,
                          last_event_ts = excluded.last_event_ts,
                          terminal_state = excluded.terminal_state
            """,
                params);
    }

    private SeenStates decodeSeenStates(UUID serviceId, String objectType, String attribute, byte[] bits, String json) {
        if (bits != null && bits.length > 0) {
            return new SeenStates(serviceId, objectType, attribute, java.util.BitSet.valueOf(bits));
        }
        try {
            if (json == null || json.isBlank()) {
                return SeenStates.empty(serviceId, objectType, attribute);
            }
            List<String> values = mapper.readValue(json, LIST_TYPE);
            if (values == null || values.isEmpty()) {
                return SeenStates.empty(serviceId, objectType, attribute);
            }
            SeenStates seenStates = SeenStates.empty(serviceId, objectType, attribute);
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    seenStates.add(codec, value);
                }
            }
            return seenStates;
        } catch (Exception ex) {
            return SeenStates.empty(serviceId, objectType, attribute);
        }
    }

    private String encodeSeenStates(SeenStates seenStates, StateCodec codec) {
        try {
            if (seenStates == null || seenStates.size() == 0) {
                return "[]";
            }
            return mapper.writeValueAsString(seenStates.toSet(codec));
        } catch (Exception ex) {
            return "[]";
        }
    }

    private byte[] encodeSeenStatesBits(SeenStates seenStates) {
        if (seenStates == null || seenStates.size() == 0) {
            return new byte[0];
        }
        return seenStates.bits().toByteArray();
    }

    @Override
    public List<TransitionInferenceCandidate> findEligible(
            UUID serviceId, String objectType, Instant cutoff, int limit) {
        if (serviceId == null || objectType == null || objectType.isBlank() || cutoff == null || limit <= 0) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("cutoff", java.sql.Timestamp.from(cutoff))
                .addValue("limit", limit);

        List<TransitionInferenceCandidate> results = new ArrayList<>();
        jdbc.query(
                """
                select service_id, object_type, object_id, attribute, last_state, seen_states, seen_states_bits, last_event_ts, terminal_state
                from obsinity.object_transition_snapshots
                where service_id = :service_id
                  and object_type = :object_type
                  and terminal_state is null
                  and last_event_ts is not null
                  and last_event_ts <= :cutoff
                order by last_event_ts asc
                limit :limit
                """,
                params,
                rs -> {
                    Instant lastEventTs = rs.getTimestamp("last_event_ts") != null
                            ? rs.getTimestamp("last_event_ts").toInstant()
                            : null;
                    results.add(new TransitionInferenceCandidate(
                            (UUID) rs.getObject("service_id"),
                            rs.getString("object_type"),
                            rs.getString("object_id"),
                            rs.getString("attribute"),
                            rs.getString("last_state"),
                            lastEventTs,
                            rs.getString("terminal_state")));
                });
        return results.isEmpty() ? List.of() : List.copyOf(results);
    }
}

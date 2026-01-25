package com.obsinity.service.core.state.transition.codec;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcStateCodec implements StateCodec {
    private final NamedParameterJdbcTemplate jdbc;
    private final Map<Key, Cache> caches = new ConcurrentHashMap<>();

    public JdbcStateCodec(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public int toId(UUID serviceId, String objectType, String attribute, String state) {
        if (serviceId == null || objectType == null || attribute == null || state == null) {
            return -1;
        }
        Key key = new Key(serviceId, objectType, attribute);
        Cache cache = caches.computeIfAbsent(key, k -> new Cache());
        Integer cached = cache.stateToId.get(state);
        if (cached != null) {
            return cached;
        }
        Integer existing = jdbc
                .query(
                        """
                select state_id
                from obsinity.object_transition_state_registry
                where service_id = :service_id
                  and object_type = :object_type
                  and attribute = :attribute
                  and state_value = :state_value
                """,
                        new MapSqlParameterSource()
                                .addValue("service_id", serviceId)
                                .addValue("object_type", objectType)
                                .addValue("attribute", attribute)
                                .addValue("state_value", state),
                        (rs, rowNum) -> rs.getInt("state_id"))
                .stream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            cache.stateToId.put(state, existing);
            cache.idToState.put(existing, state);
            return existing;
        }
        int nextId = allocateNextId(serviceId, objectType, attribute);
        jdbc.update(
                """
            insert into obsinity.object_transition_state_registry(
                service_id,
                object_type,
                attribute,
                state_value,
                state_id)
            values (:service_id, :object_type, :attribute, :state_value, :state_id)
            on conflict (service_id, object_type, attribute, state_value)
            do nothing
            """,
                new MapSqlParameterSource()
                        .addValue("service_id", serviceId)
                        .addValue("object_type", objectType)
                        .addValue("attribute", attribute)
                        .addValue("state_value", state)
                        .addValue("state_id", nextId));
        Integer finalId = jdbc
                .query(
                        """
                select state_id
                from obsinity.object_transition_state_registry
                where service_id = :service_id
                  and object_type = :object_type
                  and attribute = :attribute
                  and state_value = :state_value
                """,
                        new MapSqlParameterSource()
                                .addValue("service_id", serviceId)
                                .addValue("object_type", objectType)
                                .addValue("attribute", attribute)
                                .addValue("state_value", state),
                        (rs, rowNum) -> rs.getInt("state_id"))
                .stream()
                .findFirst()
                .orElse(nextId);
        cache.stateToId.put(state, finalId);
        cache.idToState.put(finalId, state);
        return finalId;
    }

    @Override
    public String fromId(UUID serviceId, String objectType, String attribute, int id) {
        if (serviceId == null || objectType == null || attribute == null || id < 0) {
            return null;
        }
        Key key = new Key(serviceId, objectType, attribute);
        Cache cache = caches.computeIfAbsent(key, k -> new Cache());
        String cached = cache.idToState.get(id);
        if (cached != null) {
            return cached;
        }
        String state = jdbc
                .query(
                        """
                select state_value
                from obsinity.object_transition_state_registry
                where service_id = :service_id
                  and object_type = :object_type
                  and attribute = :attribute
                  and state_id = :state_id
                """,
                        new MapSqlParameterSource()
                                .addValue("service_id", serviceId)
                                .addValue("object_type", objectType)
                                .addValue("attribute", attribute)
                                .addValue("state_id", id),
                        (rs, rowNum) -> rs.getString("state_value"))
                .stream()
                .findFirst()
                .orElse(null);
        if (state != null) {
            cache.idToState.put(id, state);
            cache.stateToId.put(state, id);
        }
        return state;
    }

    @Override
    public List<String> decode(UUID serviceId, String objectType, String attribute, BitSet bits) {
        if (bits == null || bits.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<String> states = new java.util.ArrayList<>(bits.cardinality());
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            String state = fromId(serviceId, objectType, attribute, i);
            if (state != null) {
                states.add(state);
            }
        }
        return states.isEmpty() ? List.of() : List.copyOf(states);
    }

    private int allocateNextId(UUID serviceId, String objectType, String attribute) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("service_id", serviceId)
                .addValue("object_type", objectType)
                .addValue("attribute", attribute);
        Integer id = jdbc
                .query(
                        """
                with updated as (
                    update obsinity.object_transition_state_registry_seq
                    set next_id = next_id + 1
                    where service_id = :service_id
                      and object_type = :object_type
                      and attribute = :attribute
                    returning next_id - 1 as id
                ), inserted as (
                    insert into obsinity.object_transition_state_registry_seq(
                        service_id, object_type, attribute, next_id)
                    select :service_id, :object_type, :attribute, 1
                    where not exists (
                        select 1 from obsinity.object_transition_state_registry_seq
                        where service_id = :service_id
                          and object_type = :object_type
                          and attribute = :attribute
                    )
                    returning 0 as id
                )
                select id from updated
                union all
                select id from inserted
                """,
                        params,
                        (rs, rowNum) -> rs.getInt("id"))
                .stream()
                .findFirst()
                .orElse(0);
        return id;
    }

    private record Key(UUID serviceId, String objectType, String attribute) {}

    private static final class Cache {
        private final Map<String, Integer> stateToId = new ConcurrentHashMap<>();
        private final Map<Integer, String> idToState = new ConcurrentHashMap<>();
    }
}

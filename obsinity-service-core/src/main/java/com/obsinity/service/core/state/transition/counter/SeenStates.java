package com.obsinity.service.core.state.transition.counter;

import com.obsinity.service.core.state.transition.codec.StateCodec;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class SeenStates {
    private final UUID serviceId;
    private final String objectType;
    private final String attribute;
    private final BitSet bits;

    public SeenStates(UUID serviceId, String objectType, String attribute, BitSet bits) {
        this.serviceId = serviceId;
        this.objectType = objectType;
        this.attribute = attribute;
        this.bits = bits == null ? new BitSet() : (BitSet) bits.clone();
    }

    public static SeenStates empty(UUID serviceId, String objectType, String attribute) {
        return new SeenStates(serviceId, objectType, attribute, new BitSet());
    }

    public UUID serviceId() {
        return serviceId;
    }

    public String objectType() {
        return objectType;
    }

    public String attribute() {
        return attribute;
    }

    public BitSet bits() {
        return (BitSet) bits.clone();
    }

    public int size() {
        return bits.cardinality();
    }

    public boolean contains(StateCodec codec, String state) {
        int id = codec.toId(serviceId, objectType, attribute, state);
        return id >= 0 && bits.get(id);
    }

    public boolean add(StateCodec codec, String state) {
        int id = codec.toId(serviceId, objectType, attribute, state);
        if (id < 0) {
            return false;
        }
        boolean existed = bits.get(id);
        bits.set(id);
        return !existed;
    }

    public Set<String> toSet(StateCodec codec) {
        List<String> states = codec.decode(serviceId, objectType, attribute, bits);
        return states.isEmpty() ? Set.of() : Set.copyOf(states);
    }

    public List<String> toList(StateCodec codec) {
        return codec.decode(serviceId, objectType, attribute, bits);
    }
}

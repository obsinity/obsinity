package com.obsinity.service.core.state.transition.codec;

import java.util.BitSet;
import java.util.List;
import java.util.UUID;

public interface StateCodec {
    int toId(UUID serviceId, String objectType, String attribute, String state);

    String fromId(UUID serviceId, String objectType, String attribute, int id);

    List<String> decode(UUID serviceId, String objectType, String attribute, BitSet bits);
}

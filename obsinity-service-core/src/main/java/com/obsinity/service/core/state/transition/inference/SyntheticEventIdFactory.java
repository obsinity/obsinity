package com.obsinity.service.core.state.transition.inference;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SyntheticEventIdFactory {
    public String build(
            String objectType, String objectId, String attribute, String ruleId, Instant inferredTs, String state) {
        String seed = objectType + "|" + objectId + "|" + attribute + "|" + ruleId + "|" + inferredTs.toEpochMilli()
                + "|" + state;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}

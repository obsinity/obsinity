package com.obsinity.controller.rest.grafana;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

final class FlexibleLongDeserializer extends JsonDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String text = parser.getValueAsString();
        if (text == null) {
            return parser.getValueAsLong();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return Long.valueOf(trimmed);
        } catch (NumberFormatException ex) {
            return (Long) context.handleWeirdStringValue(Long.class, trimmed, "Expected long");
        }
    }
}

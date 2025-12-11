package com.obsinity.service.core.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Wire-friendly response format toggle. Defaults to {@link #ROW} when the caller does not request a
 * specific representation.
 */
public enum ResponseFormat {
    ROW("row"),
    COLUMNAR("columnar");

    private final String wireValue;

    ResponseFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonCreator
    public static ResponseFormat fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ResponseFormat format : values()) {
            if (format.wireValue.equals(normalized)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unsupported response format: " + value);
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public static ResponseFormat defaulted(ResponseFormat requested) {
        return requested == null ? ROW : requested;
    }
}

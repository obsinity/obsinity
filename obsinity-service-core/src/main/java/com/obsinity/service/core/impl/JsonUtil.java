package com.obsinity.service.core.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class JsonUtil {
    private static final ObjectMapper M = new ObjectMapper();
    private static final ObjectWriter PRETTY;

    static {
        M.findAndRegisterModules();
        M.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        PRETTY = M.writerWithDefaultPrettyPrinter();
    }

    private JsonUtil() {}

    public static String toJson(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON encode failed", e);
        }
    }

    public static String toPrettyJson(Object o) {
        try {
            return PRETTY.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Pretty JSON encode failed", e);
        }
    }
}

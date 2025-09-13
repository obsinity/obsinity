package com.obsinity.service.core.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

class JsonUtil {
    private static final ObjectMapper M = new ObjectMapper();

    static String toJson(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON encode failed", e);
        }
    }
}

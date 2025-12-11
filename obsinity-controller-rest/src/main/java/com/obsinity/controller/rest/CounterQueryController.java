package com.obsinity.controller.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.api.ResponseFormat;
import com.obsinity.service.core.counter.CounterQueryRequest;
import com.obsinity.service.core.counter.CounterQueryResult;
import com.obsinity.service.core.counter.CounterQueryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/query", produces = MediaType.APPLICATION_JSON_VALUE)
public class CounterQueryController {

    private static final String QUERY_PATH = "/api/query/counters";

    private final CounterQueryService queryService;
    private final ObjectMapper mapper;

    public CounterQueryController(CounterQueryService queryService, ObjectMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @PostMapping(path = "/counters", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CounterQueryHalResponse query(@RequestBody CounterQueryRequest request) {
        CounterQueryResult result = queryService.runQuery(request);
        ResponseFormat format = ResponseFormat.defaulted(request.format());
        return CounterQueryHalResponse.from(QUERY_PATH, request, result, format, mapper);
    }
}

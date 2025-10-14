package com.obsinity.controller.rest;

import com.obsinity.service.core.counter.CounterQueryRequest;
import com.obsinity.service.core.counter.CounterQueryResult;
import com.obsinity.service.core.counter.CounterQueryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/counters", produces = MediaType.APPLICATION_JSON_VALUE)
public class CounterQueryController {

    private final CounterQueryService queryService;

    public CounterQueryController(CounterQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping(path = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CounterQueryResult query(@RequestBody CounterQueryRequest request) {
        return queryService.runQuery(request);
    }
}

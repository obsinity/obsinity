package com.obsinity.controller.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.api.ResponseFormat;
import com.obsinity.service.core.histogram.HistogramQueryRequest;
import com.obsinity.service.core.histogram.HistogramQueryResult;
import com.obsinity.service.core.histogram.HistogramQueryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/histograms", produces = MediaType.APPLICATION_JSON_VALUE)
public class HistogramQueryController {

    private static final String QUERY_PATH = "/api/histograms/query";

    private final HistogramQueryService queryService;
    private final ObjectMapper mapper;

    public HistogramQueryController(HistogramQueryService queryService, ObjectMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @PostMapping(path = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public HistogramQueryHalResponse query(@RequestBody HistogramQueryRequest request) {
        HistogramQueryResult result = queryService.runQuery(request);
        ResponseFormat format = ResponseFormat.defaulted(request.format());
        return HistogramQueryHalResponse.from(QUERY_PATH, request, result, format, mapper);
    }
}

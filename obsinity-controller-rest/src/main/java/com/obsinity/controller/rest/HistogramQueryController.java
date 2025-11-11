package com.obsinity.controller.rest;

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

    public HistogramQueryController(HistogramQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping(path = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public HistogramQueryHalResponse query(@RequestBody HistogramQueryRequest request) {
        HistogramQueryResult result = queryService.runQuery(request);
        return HistogramQueryHalResponse.from(QUERY_PATH, request, result);
    }
}

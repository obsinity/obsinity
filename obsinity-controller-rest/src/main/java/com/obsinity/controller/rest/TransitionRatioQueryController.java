package com.obsinity.controller.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.api.FrictionlessData;
import com.obsinity.service.core.api.ResponseFormat;
import com.obsinity.service.core.state.query.TransitionRatioQueryRequest;
import com.obsinity.service.core.state.query.TransitionRatioQueryResult;
import com.obsinity.service.core.state.query.TransitionRatioQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/query", produces = MediaType.APPLICATION_JSON_VALUE)
public class TransitionRatioQueryController {

    private static final String QUERY_PATH = "/api/query/transition-ratios";

    private final TransitionRatioQueryService queryService;
    private final ObjectMapper mapper;

    public TransitionRatioQueryController(TransitionRatioQueryService queryService, ObjectMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @PostMapping(path = "/transition-ratios", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransitionRatioQueryHalResponse query(@RequestBody TransitionRatioQueryRequest request) {
        TransitionRatioQueryResult result = queryService.runQuery(request);
        ResponseFormat format = ResponseFormat.defaulted(request.format());
        return TransitionRatioQueryHalResponse.from(QUERY_PATH, request, result, format, mapper);
    }

    public record TransitionRatioQueryHalResponse(
            int count, long totalCount, Object data, Map<String, HalLink> links, String format) {

        record Data(List<TransitionRatioQueryResult.TransitionRatioEntry> transitions) {}

        record HalLink(String href, String method, Object body) {}

        static TransitionRatioQueryHalResponse from(
                String href,
                TransitionRatioQueryRequest request,
                TransitionRatioQueryResult result,
                ResponseFormat responseFormat,
                ObjectMapper mapper) {
            int count = result.transitions().size();
            long totalCount = result.totalCount();
            ResponseFormat format = ResponseFormat.defaulted(responseFormat);
            Map<String, HalLink> links = buildLinks(href, request);
            Object data = format == ResponseFormat.COLUMNAR
                    ? FrictionlessData.columnar(toRows(result), mapper)
                    : new Data(result.transitions());
            return new TransitionRatioQueryHalResponse(count, totalCount, data, links, format.wireValue());
        }

        private static List<Map<String, Object>> toRows(TransitionRatioQueryResult result) {
            return result.transitions().stream()
                    .map(entry -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("fromState", entry.fromState());
                        row.put("toState", entry.toState());
                        row.put("count", entry.count());
                        row.put("ratio", entry.ratio());
                        return row;
                    })
                    .toList();
        }

        private static Map<String, HalLink> buildLinks(String href, TransitionRatioQueryRequest request) {
            Map<String, HalLink> links = new LinkedHashMap<>();
            links.put("self", new HalLink(href, "POST", request));
            return links;
        }
    }
}

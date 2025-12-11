package com.obsinity.controller.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.api.FrictionlessData;
import com.obsinity.service.core.api.ResponseFormat;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryRequest;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryResult;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryResult.StateCountTimeseriesWindow;
import com.obsinity.service.core.state.query.StateCountTimeseriesQueryService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/query", produces = MediaType.APPLICATION_JSON_VALUE)
public class StateCountTimeseriesQueryController {

    private static final String QUERY_PATH = "/api/query/state-count-timeseries";

    private final StateCountTimeseriesQueryService queryService;
    private final ObjectMapper mapper;

    public StateCountTimeseriesQueryController(StateCountTimeseriesQueryService queryService, ObjectMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @PostMapping(path = "/state-count-timeseries", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StateCountTimeseriesHalResponse query(@RequestBody StateCountTimeseriesQueryRequest request) {
        StateCountTimeseriesQueryResult result = queryService.runQuery(request);
        ResponseFormat format = ResponseFormat.defaulted(request.format());
        return StateCountTimeseriesHalResponse.from(QUERY_PATH, request, result, format, mapper);
    }

    public record StateCountTimeseriesHalResponse(
            int count, int total, int limit, int offset, Object data, Map<String, HalLink> links, String format) {

        record Data(java.util.List<StateCountTimeseriesWindow> intervals) {}

        record HalLink(String href, String method, Object body) {}

        static StateCountTimeseriesHalResponse from(
                String href,
                StateCountTimeseriesQueryRequest request,
                StateCountTimeseriesQueryResult result,
                ResponseFormat responseFormat,
                ObjectMapper mapper) {
            int count = result.windows().size();
            int total = result.totalIntervals();
            int offset = result.offset();
            int limit = result.limit();
            ResponseFormat format = ResponseFormat.defaulted(responseFormat);
            Map<String, HalLink> links = buildLinks(href, request, offset, limit, count, total);
            Object data = format == ResponseFormat.COLUMNAR
                    ? FrictionlessData.columnar(result.windows(), mapper)
                    : new Data(result.windows());
            return new StateCountTimeseriesHalResponse(count, total, limit, offset, data, links, format.wireValue());
        }

        private static Map<String, HalLink> buildLinks(
                String href, StateCountTimeseriesQueryRequest request, int offset, int limit, int count, int total) {
            Map<String, HalLink> links = new LinkedHashMap<>();
            links.put("self", new HalLink(href, "POST", withLimits(request, offset, limit)));
            if (offset > 0 && limit > 0) {
                int prev = Math.max(0, offset - limit);
                links.put("prev", new HalLink(href, "POST", withLimits(request, prev, limit)));
            }
            if (limit > 0 && count > 0 && offset + count < total) {
                int next = offset + limit;
                links.put("next", new HalLink(href, "POST", withLimits(request, next, limit)));
            }
            return links;
        }

        private static StateCountTimeseriesQueryRequest withLimits(
                StateCountTimeseriesQueryRequest base, int offset, int limit) {
            StateCountTimeseriesQueryRequest.Limits limits = new StateCountTimeseriesQueryRequest.Limits(offset, limit);
            return new StateCountTimeseriesQueryRequest(
                    base.serviceKey(),
                    base.objectType(),
                    base.attribute(),
                    base.states(),
                    base.interval(),
                    base.start(),
                    base.end(),
                    limits,
                    base.format());
        }
    }
}

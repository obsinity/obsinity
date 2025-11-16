package com.obsinity.controller.rest;

import com.obsinity.service.core.state.query.StateCountQueryRequest;
import com.obsinity.service.core.state.query.StateCountQueryResult;
import com.obsinity.service.core.state.query.StateCountQueryService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/query", produces = MediaType.APPLICATION_JSON_VALUE)
public class StateCountQueryController {

    private static final String QUERY_PATH = "/api/query/state-counts";

    private final StateCountQueryService queryService;

    public StateCountQueryController(StateCountQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping(path = "/state-counts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StateCountQueryHalResponse query(@RequestBody StateCountQueryRequest request) {
        StateCountQueryResult result = queryService.runQuery(request);
        return StateCountQueryHalResponse.from(QUERY_PATH, request, result);
    }

    public record StateCountQueryHalResponse(
            int count, long total, int limit, int offset, Data data, Map<String, HalLink> links) {

        record Data(java.util.List<StateCountQueryResult.StateCountEntry> states) {}

        record HalLink(String href, String method, Object body) {}

        static StateCountQueryHalResponse from(String href, StateCountQueryRequest request, StateCountQueryResult result) {
            int count = result.states().size();
            long total = result.total();
            int offset = result.offset();
            int limit = result.limit();
            Map<String, HalLink> links = buildLinks(href, request, offset, limit, count, total);
            return new StateCountQueryHalResponse(count, total, limit, offset, new Data(result.states()), links);
        }

        private static Map<String, HalLink> buildLinks(
                String href, StateCountQueryRequest request, int offset, int limit, int count, long total) {
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

        private static StateCountQueryRequest withLimits(StateCountQueryRequest base, int offset, int limit) {
            StateCountQueryRequest.Limits limits = new StateCountQueryRequest.Limits(offset, limit);
            return new StateCountQueryRequest(base.serviceKey(), base.objectType(), base.attribute(), base.states(), limits);
        }
    }
}

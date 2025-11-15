package com.obsinity.controller.rest;

import com.obsinity.service.core.state.query.StateTransitionQueryRequest;
import com.obsinity.service.core.state.query.StateTransitionQueryResult;
import com.obsinity.service.core.state.query.StateTransitionQueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(StateTransitionQueryController.QUERY_PATH)
public class StateTransitionQueryController {

    public static final String QUERY_PATH = "/api/query/state-transitions";

    private final StateTransitionQueryService queryService;

    public StateTransitionQueryController(StateTransitionQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping
    public StateTransitionQueryHalResponse query(@RequestBody StateTransitionQueryRequest request) {
        StateTransitionQueryResult result = queryService.runQuery(request);
        return StateTransitionQueryHalResponse.from(QUERY_PATH, request, result);
    }
}

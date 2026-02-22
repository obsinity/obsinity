package com.obsinity.controller.rest;

import com.obsinity.service.core.state.query.RatioQueryRequest;
import com.obsinity.service.core.state.query.RatioQueryResult;
import com.obsinity.service.core.state.query.RatioQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/query", produces = MediaType.APPLICATION_JSON_VALUE)
public class RatioQueryController {
    private final RatioQueryService ratioQueryService;

    public RatioQueryController(RatioQueryService ratioQueryService) {
        this.ratioQueryService = ratioQueryService;
    }

    @GetMapping("/ratio")
    public List<Map<String, Object>> query(
            @RequestParam("serviceKey") String serviceKey,
            @RequestParam("name") String name,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        RatioQueryResult result = ratioQueryService.runQuery(new RatioQueryRequest(serviceKey, name, from, to));
        return result.slices().stream().map(this::toRow).toList();
    }

    private Map<String, Object> toRow(RatioQueryResult.RatioSlice slice) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", slice.label());
        row.put("value", slice.value());
        if (slice.percent() != null) {
            row.put("percent", slice.percent());
        }
        if (slice.ratio() != null) {
            row.put("ratio", slice.ratio());
        }
        if (slice.rawValue() != null) {
            row.put("rawValue", slice.rawValue());
        }
        return row;
    }
}

package com.obsinity.controller.rest;

import com.obsinity.service.core.objql.OBJqlPage;
import com.obsinity.service.core.search.SearchService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/objql")
public class QueryController {

    private final SearchService search;

    public QueryController(SearchService search) {
        this.search = search;
    }

    /** POST raw OB-JQL string. Body: {"q":"...", "offset":0, "limit":100} */
    @PostMapping(
        value = "/query",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public List<Map<String, Object>> run(@RequestBody QueryBody body) {
        if (body == null || body.q == null || body.q.isBlank()) {
            throw new IllegalArgumentException("Missing 'q' field with OB-JQL");
        }
        OBJqlPage page = OBJqlPage.of(body.offset, body.limit);
        return search.query(body.q, page);
    }

    public static class QueryBody {
        public String q;
        public Long offset;    // optional (default 0)
        public Integer limit;  // optional (default 100)
    }
}

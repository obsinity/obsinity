package com.obsinity.service.core.search;

import com.obsinity.service.core.objql.OBJql;
import com.obsinity.service.core.objql.OBJqlPage;
import java.util.List;
import java.util.Map;

/** Executes OB-JQL queries and returns row maps (column -> value). */
public interface SearchService {
    default List<Map<String, Object>> query(String objql) {
        return query(objql, OBJqlPage.firstPage(), true);
    }

    default List<Map<String, Object>> query(OBJql ast) {
        return query(ast, OBJqlPage.firstPage(), true);
    }

    // Paging-friendly overloads
    default List<Map<String, Object>> query(String objql, OBJqlPage page) {
        return query(objql, page, true);
    }

    default List<Map<String, Object>> query(OBJql ast, OBJqlPage page) {
        return query(ast, page, true);
    }

    List<Map<String, Object>> query(String objql, OBJqlPage page, boolean includeTotal);

    List<Map<String, Object>> query(OBJql ast, OBJqlPage page, boolean includeTotal);
}

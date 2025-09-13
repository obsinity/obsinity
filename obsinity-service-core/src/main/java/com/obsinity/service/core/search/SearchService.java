package com.obsinity.service.core.search;

import com.obsinity.service.core.objql.OBJql;
import com.obsinity.service.core.objql.OBJqlPage;
import java.util.List;
import java.util.Map;

/** Executes OB-JQL queries and returns row maps (column -> value). */
public interface SearchService {
	List<Map<String, Object>> query(String objql);
	List<Map<String, Object>> query(OBJql ast);

	// Paging-friendly overloads
	List<Map<String, Object>> query(String objql, OBJqlPage page);
	List<Map<String, Object>> query(OBJql ast, OBJqlPage page);
}

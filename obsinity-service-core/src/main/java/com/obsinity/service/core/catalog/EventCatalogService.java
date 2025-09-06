package com.obsinity.service.core.catalog;

import java.util.List;
import java.util.Map;

public interface EventCatalogService {
    void ensureType(String type, String description);

    void addIndexPath(String type, String path);

    List<Map<String, Object>> listTypes();
}

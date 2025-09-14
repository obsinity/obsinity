package com.obsinity.service.core.index;

import java.util.List;
import java.util.UUID;

public interface AttributeIndexSpecCfgRepository {
    List<String> findIndexedAttributePaths(UUID serviceId, UUID eventTypeId);
}

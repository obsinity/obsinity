package com.obsinity.service.core.index;

import java.util.List;
import java.util.UUID;

public interface AttributeIndexSpecCfgRepository {
	/**
	 * Return attribute dot-paths to index for the given (serviceId, eventTypeId).
	 * Example rows: "http.status", "user.id", "error.code"
	 */
	List<String> findIndexedAttributePaths(UUID serviceId, UUID eventTypeId);
}

package com.obsinity.service.core.state.query;

import com.obsinity.service.core.repo.ObjectStateCountRepository;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StateCountQueryService {

    private static final int DEFAULT_LIMIT = 50;

    private final ServicesCatalogRepository servicesCatalogRepository;
    private final ObjectStateCountRepository countRepository;

    public StateCountQueryService(
            ServicesCatalogRepository servicesCatalogRepository, ObjectStateCountRepository countRepository) {
        this.servicesCatalogRepository = servicesCatalogRepository;
        this.countRepository = countRepository;
    }

    public StateCountQueryResult runQuery(StateCountQueryRequest request) {
        validate(request);
        UUID serviceId = servicesCatalogRepository.findIdByServiceKey(request.serviceKey());
        if (serviceId == null) {
            throw new IllegalArgumentException("Unknown service key: " + request.serviceKey());
        }
        int offset = request.limits() != null && request.limits().offset() != null ? Math.max(0, request.limits().offset()) : 0;
        int limit = request.limits() != null && request.limits().limit() != null
                ? Math.max(1, request.limits().limit())
                : DEFAULT_LIMIT;
        List<ObjectStateCountRepository.StateCountRow> rows =
                countRepository.list(serviceId, request.objectType(), request.attribute(), request.states(), offset, limit);
        long total = countRepository.countStates(serviceId, request.objectType(), request.attribute(), request.states());
        List<StateCountQueryResult.StateCountEntry> entries = rows.stream()
                .map(r -> new StateCountQueryResult.StateCountEntry(r.state(), r.count()))
                .toList();
        return new StateCountQueryResult(entries, offset, limit, total);
    }

    private void validate(StateCountQueryRequest request) {
        if (request == null) throw new IllegalArgumentException("query request is required");
        if (request.serviceKey() == null || request.serviceKey().isBlank()) {
            throw new IllegalArgumentException("serviceKey is required");
        }
        if (request.objectType() == null || request.objectType().isBlank()) {
            throw new IllegalArgumentException("objectType is required");
        }
        if (request.attribute() == null || request.attribute().isBlank()) {
            throw new IllegalArgumentException("attribute is required");
        }
    }
}

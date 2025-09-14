package com.obsinity.service.core.repo;

import com.obsinity.service.core.entities.AttributeIndexEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AttributeIndexRepository extends JpaRepository<AttributeIndexEntity, UUID> {

    @Modifying
    @Query(
            value =
                    """
        INSERT INTO attribute_index_registry (id, event_id, spec_json, spec_hash)
        VALUES (:id, :eventId, cast(:specJson as jsonb), :specHash)
        ON CONFLICT (event_id, spec_hash) DO NOTHING
        """,
            nativeQuery = true)
    int upsertAttributeIndex(
            @Param("id") UUID id,
            @Param("eventId") UUID eventId,
            @Param("specJson") String specJson,
            @Param("specHash") String specHash);
}

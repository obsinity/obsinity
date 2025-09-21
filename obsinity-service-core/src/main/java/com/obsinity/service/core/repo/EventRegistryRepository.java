package com.obsinity.service.core.repo;

import com.obsinity.service.core.entities.EventRegistryEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Native upserts for event_registry, metric_registry, and attribute_index_registry.
 * Keeps ingest idempotent.
 */
@Repository
public interface EventRegistryRepository extends JpaRepository<EventRegistryEntity, UUID> {

    @Modifying
    @Query(
            value =
                    """
        INSERT INTO event_registry (
          id, service_id, service, service_short, category, sub_category, event_name, event_norm, retention_ttl
        ) VALUES (
          :id, :serviceId, :service, :serviceShort, :category, :subCategory, :eventName, :eventNorm, cast(:retentionTtl as interval)
        )
        ON CONFLICT (service_id, event_norm) DO UPDATE SET
          updated_at = now(),
          retention_ttl = COALESCE(EXCLUDED.retention_ttl, event_registry.retention_ttl)
        """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("serviceId") UUID serviceId,
            @Param("service") String service,
            @Param("serviceShort") String serviceShort,
            @Param("category") String category,
            @Param("subCategory") String subCategory,
            @Param("eventName") String eventName,
            @Param("eventNorm") String eventNorm,
            @Param("retentionTtl") String retentionTtl);

    @Query(
            value = "SELECT id FROM event_registry WHERE service_id = :serviceId AND event_norm = :eventNorm",
            nativeQuery = true)
    UUID findIdByServiceIdAndEventNorm(@Param("serviceId") UUID serviceId, @Param("eventNorm") String eventNorm);
}

package com.obsinity.service.core.repo;

import com.obsinity.service.core.entities.EventRegistryEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Native upserts for event_registry_cfg, metric_cfg, and attribute_index_cfg.
 * Keeps ingest idempotent.
 */
@Repository
public interface EventRegistryRepository extends JpaRepository<EventRegistryEntity, UUID> {

    @Modifying
    @Query(
            value =
                    """
        INSERT INTO event_registry_cfg (id, service, event_name, event_norm)
        VALUES (:id, :service, :eventName, :eventNorm)
        ON CONFLICT (service, event_norm) DO UPDATE SET updated_at = now()
        """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("service") String service,
            @Param("eventName") String eventName,
            @Param("eventNorm") String eventNorm);

    @Query(
            value = "SELECT id FROM event_registry_cfg WHERE service = :service AND event_norm = :eventNorm",
            nativeQuery = true)
    UUID findIdByServiceAndEventNorm(@Param("service") String service, @Param("eventNorm") String eventNorm);
}

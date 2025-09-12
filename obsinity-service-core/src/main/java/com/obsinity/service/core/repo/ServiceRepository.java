package com.obsinity.service.core.repo;

import com.obsinity.service.core.entities.ServiceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {

    /**
     * Lightweight projection for config loader —
     * fetches id, short name, and updatedAt only.
     */
    @Query("select s from ServiceEntity s")
    List<ServiceEntity> findAllLite();

    Optional<ServiceEntity> findByServiceShort(String serviceShort);
}

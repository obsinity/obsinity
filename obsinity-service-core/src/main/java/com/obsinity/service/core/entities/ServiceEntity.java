package com.obsinity.service.core.entities;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Root service registry entry.
 * One row per service; event types hang off this.
 */
@Entity
@Table(name = "cfg_service")
public class ServiceEntity {

    @Id
    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "service_short", nullable = false, unique = true)
    private String serviceShort;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    // --- constructors ---
    public ServiceEntity() {}

    public ServiceEntity(UUID serviceId, String serviceShort, Instant updatedAt) {
        this.serviceId = serviceId;
        this.serviceShort = serviceShort;
        this.updatedAt = updatedAt;
    }

    // --- getters/setters ---
    public UUID getServiceId() {
        return serviceId;
    }

    public void setServiceId(UUID serviceId) {
        this.serviceId = serviceId;
    }

    public String getServiceShort() {
        return serviceShort;
    }

    public void setServiceShort(String serviceShort) {
        this.serviceShort = serviceShort;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

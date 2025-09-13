package com.obsinity.service.core.entities;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_registry_cfg")
public class EventRegistryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "service", nullable = false)
    private String service;

    @Column(name = "event_name", nullable = false)
    private String eventName;

    @Column(name = "event_norm", nullable = false)
    private String eventNorm;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public EventRegistryEntity() {}

    // --- getters & setters ---

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getEventNorm() {
        return eventNorm;
    }

    public void setEventNorm(String eventNorm) {
        this.eventNorm = eventNorm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

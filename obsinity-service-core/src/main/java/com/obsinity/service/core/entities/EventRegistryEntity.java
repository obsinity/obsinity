package com.obsinity.service.core.entities;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_registry")
public class EventRegistryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "service", nullable = false)
    private String service;

    @Column(name = "service_id", nullable = false)
    private java.util.UUID serviceId;

    @Column(name = "service_short", nullable = false)
    private String serviceShort;

    @Column(name = "category")
    private String category;

    @Column(name = "sub_category")
    private String subCategory;

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

    public java.util.UUID getServiceId() {
        return serviceId;
    }

    public void setServiceId(java.util.UUID serviceId) {
        this.serviceId = serviceId;
    }

    public String getServiceShort() {
        return serviceShort;
    }

    public void setServiceShort(String serviceShort) {
        this.serviceShort = serviceShort;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSubCategory() {
        return subCategory;
    }

    public void setSubCategory(String subCategory) {
        this.subCategory = subCategory;
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

package com.obsinity.service.core.entities;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "metric_cfg")
public class MetricConfigEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "spec_json", columnDefinition = "jsonb")
    private String specJson;

    @Column(name = "spec_hash", nullable = false)
    private String specHash;

    @Column(name = "keyed_keys", columnDefinition = "text[]")
    private String keyedKeys;

    @Column(name = "rollups", columnDefinition = "text[]")
    private String rollups;

    @Column(name = "bucket_layout_hash")
    private String bucketLayoutHash;

    @Column(name = "filters_json", columnDefinition = "jsonb")
    private String filtersJson;

    @Column(name = "backfill_window")
    private String backfillWindow;

    @Column(name = "cutover_at")
    private Instant cutoverAt;

    @Column(name = "grace_until")
    private Instant graceUntil;

    @Column(name = "state")
    private String state;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public MetricConfigEntity() {}

    // --- getters & setters ---

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSpecJson() {
        return specJson;
    }

    public void setSpecJson(String specJson) {
        this.specJson = specJson;
    }

    public String getSpecHash() {
        return specHash;
    }

    public void setSpecHash(String specHash) {
        this.specHash = specHash;
    }

    public String getKeyedKeys() {
        return keyedKeys;
    }

    public void setKeyedKeys(String keyedKeys) {
        this.keyedKeys = keyedKeys;
    }

    public String getRollups() {
        return rollups;
    }

    public void setRollups(String rollups) {
        this.rollups = rollups;
    }

    public String getBucketLayoutHash() {
        return bucketLayoutHash;
    }

    public void setBucketLayoutHash(String bucketLayoutHash) {
        this.bucketLayoutHash = bucketLayoutHash;
    }

    public String getFiltersJson() {
        return filtersJson;
    }

    public void setFiltersJson(String filtersJson) {
        this.filtersJson = filtersJson;
    }

    public String getBackfillWindow() {
        return backfillWindow;
    }

    public void setBackfillWindow(String backfillWindow) {
        this.backfillWindow = backfillWindow;
    }

    public Instant getCutoverAt() {
        return cutoverAt;
    }

    public void setCutoverAt(Instant cutoverAt) {
        this.cutoverAt = cutoverAt;
    }

    public Instant getGraceUntil() {
        return graceUntil;
    }

    public void setGraceUntil(Instant graceUntil) {
        this.graceUntil = graceUntil;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public void setMetricKey(String metricKey) {
        this.metricKey = metricKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

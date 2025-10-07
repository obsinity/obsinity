-- =========================
-- Baseline schema for Obsinity service core
-- =========================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ===========================================
-- Canonical raw inbox aligned to EventEnvelope
-- ===========================================
CREATE TABLE IF NOT EXISTS events_raw (
  event_id         UUID NOT NULL,
  event_type_id    UUID NOT NULL,
  parent_event_id  UUID,

  service_partition_key TEXT        NOT NULL,
  event_type       TEXT        NOT NULL,
  kind             TEXT,
  attributes       JSONB       NOT NULL DEFAULT '{}'::jsonb,
  started_at       TIMESTAMPTZ NOT NULL,
  completed_at     TIMESTAMPTZ,
  duration_nanos   BIGINT,
  received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

  trace_id         TEXT,
  span_id          TEXT,
  parent_span_id   TEXT,
  correlation_id   TEXT,
  status           TEXT,

  PRIMARY KEY (service_partition_key, started_at, event_id)
)
PARTITION BY LIST (service_partition_key);

CREATE TABLE IF NOT EXISTS events_raw_default
  PARTITION OF events_raw DEFAULT;

-- ==============================================================
-- DATA index (per-event attribute rows) — PARTITIONED TABLE
-- ==============================================================
CREATE TABLE IF NOT EXISTS event_attr_index (
  service_partition_key  TEXT        NOT NULL,
  started_at    TIMESTAMPTZ NOT NULL,
  service_id     UUID        NOT NULL,
  event_type_id  UUID        NOT NULL,
  event_id       UUID        NOT NULL,
  attr_name      TEXT        NOT NULL,
  attr_value     TEXT        NOT NULL,
  PRIMARY KEY (service_partition_key, started_at, event_id, attr_name, attr_value)
)
PARTITION BY LIST (service_partition_key);

CREATE TABLE IF NOT EXISTS event_attr_index_default
  PARTITION OF event_attr_index DEFAULT;

-- ==============================================================
-- Dead-letter storage for rejected events
-- ==============================================================
CREATE TABLE IF NOT EXISTS event_dead_letters (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  service_key TEXT,
  event_type  TEXT,
  event_id    TEXT,
  reason      TEXT        NOT NULL,
  error       TEXT,
  payload     JSONB       NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_event_dead_letters_service
  ON event_dead_letters(service_key);

-- ================================================
-- Attribute distinct values (per service + path)
-- ================================================
CREATE TABLE IF NOT EXISTS attribute_distinct_values (
  service_partition_key  TEXT        NOT NULL,
  attr_name      TEXT        NOT NULL,
  attr_value     TEXT        NOT NULL,
  first_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen      TIMESTAMPTZ NOT NULL DEFAULT now(),
  seen_count     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (service_partition_key, attr_name, attr_value)
);

CREATE INDEX IF NOT EXISTS ix_attr_distinct_service_attr
  ON attribute_distinct_values(service_partition_key, attr_name);

-- ================================================
-- Services Catalog
-- ================================================
CREATE TABLE IF NOT EXISTS service_registry (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  service_key          TEXT NOT NULL UNIQUE,
  service_partition_key TEXT NOT NULL UNIQUE,
  description          TEXT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

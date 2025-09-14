-- =========================
-- V1__init.sql (clean start, no triggers)
-- =========================

-- Extensions (UUID & hashing helpers)
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ===========================================
-- Canonical raw inbox aligned to EventEnvelope
-- ===========================================
CREATE TABLE IF NOT EXISTS events_raw (
  event_id         UUID NOT NULL,
  event_type_id    UUID NOT NULL,
  parent_event_id  UUID,

  -- Core envelope
  service_short    TEXT        NOT NULL,
  event_type       TEXT        NOT NULL,
  attributes       JSONB       NOT NULL DEFAULT '{}'::jsonb,
  occurred_at      TIMESTAMPTZ NOT NULL,              -- producer time
  received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),-- ingest time

  -- OTEL/correlation
  trace_id         TEXT,
  span_id          TEXT,
  parent_span_id   TEXT,
  correlation_id   TEXT,

  PRIMARY KEY (service_short, occurred_at, event_id)
)
PARTITION BY LIST (service_short);

CREATE TABLE IF NOT EXISTS events_raw_default
  PARTITION OF events_raw DEFAULT;

-- ==============================================================
-- DATA index (per-event attribute rows) — PARTITIONED TABLE
-- Matches events_raw: LIST(service_short) -> RANGE(occurred_at weekly)
-- ==============================================================

CREATE TABLE IF NOT EXISTS event_attr_index (
  -- Partitioning & time (must mirror events_raw)
  service_short  TEXT        NOT NULL,
  occurred_at    TIMESTAMPTZ NOT NULL,

  -- Ids and attribute data
  service_id     UUID        NOT NULL,
  event_type_id  UUID        NOT NULL,
  event_id       UUID        NOT NULL,
  attr_name      TEXT        NOT NULL,
  attr_value     TEXT        NOT NULL,

  -- Primary key must include all partition columns (service_short, occurred_at)
  PRIMARY KEY (service_short, occurred_at, event_id, attr_name, attr_value)
)
PARTITION BY LIST (service_short);

-- Optional default partition (catches new services until a LIST child is created)
CREATE TABLE IF NOT EXISTS event_attr_index_default
  PARTITION OF event_attr_index DEFAULT;

-- ================================================
-- Services Catalog
-- ================================================
CREATE TABLE IF NOT EXISTS service_registry (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  service_key TEXT NOT NULL UNIQUE,         -- e.g., "obsinity-reference-service"
  short_key   TEXT NOT NULL UNIQUE,         -- 8-char sha256 hex substring
  description TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO service_registry (service_key, short_key, description)
VALUES (
  'obsinity-reference-service',
  SUBSTRING(ENCODE(DIGEST('obsinity-reference-service','sha256'),'hex') FOR 8),
  'Obsinity Reference Service (demo HTTP ingest server)'
)
ON CONFLICT (service_key) DO NOTHING;

-- ================================================
-- Config-as-Source-of-Truth Registry (Phase-1)
-- ================================================

-- 1) Event registry
CREATE TABLE IF NOT EXISTS event_registry (
  id            UUID PRIMARY KEY,             -- app supplies UUIDv7 (dev: any UUID ok)
  service_id    UUID NOT NULL REFERENCES service_registry(id) ON DELETE RESTRICT,
  service       TEXT NOT NULL,                -- human key (e.g., payments)
  service_short TEXT NOT NULL,                -- 8-char short key for partitioning (from SHA-256)
  category      TEXT,                         -- optional category for the event
  sub_category  TEXT,                         -- optional sub-category for the event
  event_name    TEXT NOT NULL,                -- canonical (e.g., "HttpRequest")
  event_norm    TEXT NOT NULL,                -- lowercase ("httprequest") for lookups
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (service_id, event_norm)
);

CREATE INDEX IF NOT EXISTS ix_event_registry_service_norm    ON event_registry(service, event_norm);
CREATE INDEX IF NOT EXISTS ix_event_registry_service         ON event_registry(service);
CREATE INDEX IF NOT EXISTS ix_event_registry_service_id      ON event_registry(service_id);
CREATE INDEX IF NOT EXISTS ix_event_registry_service_short   ON event_registry(service_short);
CREATE INDEX IF NOT EXISTS ix_event_registry_norm            ON event_registry(event_norm);
CREATE INDEX IF NOT EXISTS ix_event_registry_category        ON event_registry(category);
CREATE INDEX IF NOT EXISTS ix_event_registry_sub_category    ON event_registry(sub_category);

-- 2) Metric configs linked to event
CREATE TABLE IF NOT EXISTS metric_registry (
  id                 UUID PRIMARY KEY,      -- app may supply UUIDv7; dev ok to use v4
  event_id           UUID NOT NULL REFERENCES event_registry(id) ON DELETE CASCADE,
  name               TEXT NOT NULL,         -- display label
  type               TEXT NOT NULL,         -- COUNTER | HISTOGRAM | GAUGE | STATE_COUNTER

  -- Deterministic identity from definition
  metric_key         TEXT NOT NULL,         -- short SHA-256 hex (e.g., 32 chars)
  UNIQUE (event_id, metric_key),

  spec_json          JSONB NOT NULL,        -- normalized config (jsonified yaml)
  spec_hash          TEXT  NOT NULL,        -- sha256 of spec_json (fast no-op check)

  keyed_keys         TEXT[] NOT NULL DEFAULT '{}',
  rollups            TEXT[] NOT NULL DEFAULT '{}',
  bucket_layout_hash TEXT,                  -- histograms only
  filters_json       JSONB NOT NULL DEFAULT '{}'::jsonb,

  backfill_window    INTERVAL,              -- optional
  state              TEXT NOT NULL DEFAULT 'PENDING',
  cutover_at         TIMESTAMPTZ,
  grace_until        TIMESTAMPTZ,

  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_metric_registry_event      ON metric_registry(event_id);
CREATE INDEX IF NOT EXISTS ix_metric_registry_state      ON metric_registry(state);
CREATE INDEX IF NOT EXISTS ix_metric_registry_cutover    ON metric_registry(cutover_at);
CREATE INDEX IF NOT EXISTS ix_metric_registry_grace      ON metric_registry(grace_until);

-- Constraints (no IF NOT EXISTS support -> guarded DO blocks)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'chk_metric_type'
      AND conrelid = 'metric_registry'::regclass
  ) THEN
    ALTER TABLE metric_registry
      ADD CONSTRAINT chk_metric_type
      CHECK (type IN ('COUNTER','HISTOGRAM','GAUGE','STATE_COUNTER'));
  END IF;
END$$;

-- Add FKs now that referenced tables exist
ALTER TABLE IF EXISTS events_raw
  ADD CONSTRAINT fk_events_raw_event_type
  FOREIGN KEY (event_type_id) REFERENCES event_registry(id) ON DELETE RESTRICT;

ALTER TABLE IF EXISTS event_attr_index
  ADD CONSTRAINT fk_event_attr_index_service
  FOREIGN KEY (service_id) REFERENCES service_registry(id) ON DELETE RESTRICT;

ALTER TABLE IF EXISTS event_attr_index
  ADD CONSTRAINT fk_event_attr_index_event
  FOREIGN KEY (event_type_id) REFERENCES event_registry(id) ON DELETE RESTRICT;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'chk_spec_json_is_object'
      AND conrelid = 'metric_registry'::regclass
  ) THEN
    ALTER TABLE metric_registry
      ADD CONSTRAINT chk_spec_json_is_object
      CHECK (jsonb_typeof(spec_json) = 'object');
  END IF;
END$$;

-- 3) Attribute index definitions (optional)
CREATE TABLE IF NOT EXISTS attribute_index_registry (
  id           UUID PRIMARY KEY,            -- app supplies UUIDv7; dev ok to use v4
  event_id     UUID NOT NULL REFERENCES event_registry(id) ON DELETE CASCADE,
  spec_json    JSONB NOT NULL,              -- e.g., {"indexed":["service","method","status_code"]}
  spec_hash    TEXT  NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (event_id, spec_hash)
);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'chk_attr_idx_json'
      AND conrelid = 'attribute_index_registry'::regclass
  ) THEN
    ALTER TABLE attribute_index_registry
      ADD CONSTRAINT chk_attr_idx_json
      CHECK (jsonb_typeof(spec_json) = 'object');
  END IF;
END$$;

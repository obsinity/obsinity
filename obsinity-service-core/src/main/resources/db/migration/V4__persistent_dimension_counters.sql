-- ================================================
-- Persistent Dimension Counters (PDCs)
-- ================================================

CREATE TABLE IF NOT EXISTS obsinity.event_registry (
  event_id UUID PRIMARY KEY,
  first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS obsinity.duplicate_event_audit (
  event_id UUID NOT NULL,
  first_seen_at TIMESTAMPTZ,
  duplicate_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  event_type TEXT,
  dimensions_json JSONB,
  payload_json JSONB
);

CREATE INDEX IF NOT EXISTS ix_duplicate_event_audit_event_id
  ON obsinity.duplicate_event_audit(event_id);

CREATE TABLE IF NOT EXISTS obsinity.persistent_counters (
  counter_name TEXT NOT NULL,
  dimension_key CHAR(64) NOT NULL,
  dimensions_json JSONB,
  value BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  floor_at_zero BOOLEAN NOT NULL DEFAULT false,
  PRIMARY KEY (counter_name, dimension_key)
);

CREATE INDEX IF NOT EXISTS ix_persistent_counters_name
  ON obsinity.persistent_counters(counter_name);

CREATE INDEX IF NOT EXISTS ix_persistent_counters_dimension
  ON obsinity.persistent_counters(dimension_key);

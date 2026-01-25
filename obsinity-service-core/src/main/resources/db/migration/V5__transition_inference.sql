-- ================================================
-- Synthetic terminal inference records
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.synthetic_terminal_events (
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  object_id TEXT NOT NULL,
  attribute TEXT NOT NULL,
  rule_id TEXT NOT NULL,
  synthetic_event_id TEXT NOT NULL,
  synthetic_ts TIMESTAMPTZ NOT NULL,
  synthetic_state TEXT NOT NULL,
  emit_service_id TEXT NOT NULL,
  reason TEXT,
  origin TEXT NOT NULL,
  status TEXT NOT NULL,
  last_event_ts TIMESTAMPTZ NOT NULL,
  last_state TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  transition_footprint JSONB,
  PRIMARY KEY (synthetic_event_id),
  UNIQUE (service_id, object_type, object_id, attribute, rule_id, synthetic_ts)
);

CREATE INDEX IF NOT EXISTS ix_synthetic_terminal_events_object
  ON obsinity.synthetic_terminal_events(service_id, object_type, object_id, attribute);

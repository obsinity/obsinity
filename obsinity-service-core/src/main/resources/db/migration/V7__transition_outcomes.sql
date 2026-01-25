-- ================================================
-- Transition outcome indexing for funnels
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.object_transition_state_first_seen (
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  object_id TEXT NOT NULL,
  attribute TEXT NOT NULL,
  state TEXT NOT NULL,
  first_seen_ts TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (service_id, object_type, object_id, attribute, state)
);

CREATE INDEX IF NOT EXISTS ix_object_transition_state_first_seen_state
  ON obsinity.object_transition_state_first_seen(object_type, attribute, state, first_seen_ts);

CREATE TABLE IF NOT EXISTS obsinity.object_transition_outcomes (
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  object_id TEXT NOT NULL,
  attribute TEXT NOT NULL,
  terminal_state TEXT,
  terminal_ts TIMESTAMPTZ,
  origin TEXT,
  synthetic_event_id TEXT,
  superseded_by_event_id TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (service_id, object_type, object_id, attribute)
);

CREATE INDEX IF NOT EXISTS ix_object_transition_outcomes_terminal
  ON obsinity.object_transition_outcomes(object_type, attribute, terminal_state, terminal_ts);

-- ================================================
-- Seen states bitset + registry + outcome indices
-- ================================================
ALTER TABLE obsinity.object_transition_snapshots
  ADD COLUMN IF NOT EXISTS seen_states_bits BYTEA;

CREATE TABLE IF NOT EXISTS obsinity.object_transition_state_registry (
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  attribute TEXT NOT NULL,
  state_value TEXT NOT NULL,
  state_id INT NOT NULL,
  PRIMARY KEY (service_id, object_type, attribute, state_value),
  UNIQUE (service_id, object_type, attribute, state_id)
);

CREATE TABLE IF NOT EXISTS obsinity.object_transition_state_registry_seq (
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  attribute TEXT NOT NULL,
  next_id INT NOT NULL,
  PRIMARY KEY (service_id, object_type, attribute)
);

CREATE INDEX IF NOT EXISTS ix_object_transition_state_registry_lookup
  ON obsinity.object_transition_state_registry(service_id, object_type, attribute, state_id);

CREATE INDEX IF NOT EXISTS ix_transition_state_first_seen_entry
  ON obsinity.object_transition_state_first_seen(object_type, attribute, first_seen_ts);

CREATE INDEX IF NOT EXISTS ix_transition_outcomes_terminal_ts
  ON obsinity.object_transition_outcomes(object_type, attribute, terminal_ts);

CREATE INDEX IF NOT EXISTS ix_transition_outcomes_terminal_state_ts
  ON obsinity.object_transition_outcomes(object_type, attribute, terminal_state, terminal_ts);

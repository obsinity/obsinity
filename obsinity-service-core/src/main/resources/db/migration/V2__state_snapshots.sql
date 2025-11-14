-- State snapshot storage for state extractor feature

CREATE TABLE IF NOT EXISTS obs_state_snapshots (
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  object_id TEXT NOT NULL,
  attribute TEXT NOT NULL,
  state_value TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (service_id, object_type, object_id, attribute)
);

CREATE INDEX IF NOT EXISTS ix_state_snapshots_object
  ON obs_state_snapshots(object_type, object_id);

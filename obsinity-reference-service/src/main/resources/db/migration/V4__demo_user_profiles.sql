CREATE TABLE IF NOT EXISTS demo_user_profiles (
  id UUID PRIMARY KEY,
  state TEXT NOT NULL,
  state_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_demo_user_profiles_state_changed_at
  ON demo_user_profiles(state, state_changed_at);

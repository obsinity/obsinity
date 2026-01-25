-- ================================================
-- Synthetic terminal supersede tracking
-- ================================================
ALTER TABLE obsinity.synthetic_terminal_events
  ADD COLUMN IF NOT EXISTS superseded_by_event_id TEXT,
  ADD COLUMN IF NOT EXISTS superseded_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS reversed_at TIMESTAMPTZ;

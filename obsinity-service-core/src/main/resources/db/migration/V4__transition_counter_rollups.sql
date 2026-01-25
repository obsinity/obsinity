-- ================================================
-- Transition counter snapshots per object
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.object_transition_snapshots (
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  object_id TEXT NOT NULL,
  attribute TEXT NOT NULL,
  last_state TEXT,
  seen_states JSONB NOT NULL,
  last_event_ts TIMESTAMPTZ,
  terminal_state TEXT,
  PRIMARY KEY (service_id, object_type, object_id, attribute)
);

CREATE INDEX IF NOT EXISTS ix_object_transition_snapshots_type
  ON obsinity.object_transition_snapshots(object_type, attribute);

-- ================================================
-- Transition counter postings (idempotency)
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.transition_counter_postings (
  posting_id TEXT PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ================================================
-- Transition counter rollups (time series)
-- ================================================
CREATE TABLE IF NOT EXISTS obsinity.object_transition_counters (
  ts TIMESTAMPTZ NOT NULL,
  bucket VARCHAR(10) NOT NULL,
  service_id UUID NOT NULL,
  object_type TEXT NOT NULL,
  attribute TEXT NOT NULL,
  counter_name TEXT NOT NULL,
  from_state TEXT NOT NULL,
  to_state TEXT NOT NULL,
  counter_value BIGINT NOT NULL,
  PRIMARY KEY (ts, bucket, service_id, object_type, attribute, counter_name, from_state, to_state)
)
PARTITION BY LIST (bucket);

DO $$
DECLARE
    bucket_name TEXT;
    parent_table TEXT;
BEGIN
    FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
        parent_table := format('object_transition_counters_%s', lower(bucket_name));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS obsinity.%I
             PARTITION OF obsinity.object_transition_counters
             FOR VALUES IN (''%s'')
             PARTITION BY RANGE (ts);',
            parent_table,
            bucket_name
        );
    END LOOP;
END $$;

DO $$
DECLARE
    start_date DATE := (CURRENT_DATE - INTERVAL '28 days')::DATE;
    end_date   DATE := (CURRENT_DATE + INTERVAL '28 days')::DATE;
    week_start DATE;
    week_end   DATE;
    bucket_name TEXT;
    parent_table TEXT;
    partition_name TEXT;
BEGIN
    week_start := start_date;
    WHILE week_start < end_date LOOP
        week_end := week_start + INTERVAL '7 days';

        FOR bucket_name IN SELECT unnested FROM unnest(ARRAY['S5','M1','M5','H1','D1','D7']) AS unnested LOOP
            parent_table := format('object_transition_counters_%s', lower(bucket_name));
            partition_name := format(
                'object_transition_counters_%s_%s',
                to_char(week_start, 'IYYY_IW'),
                lower(bucket_name)
            );

            EXECUTE format(
                'CREATE UNLOGGED TABLE IF NOT EXISTS obsinity.%I
                 PARTITION OF obsinity.%I
                 FOR VALUES FROM (TIMESTAMPTZ %L) TO (TIMESTAMPTZ %L);',
                partition_name,
                parent_table,
                week_start,
                week_end
            );

            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON obsinity.%I(object_type, attribute, counter_name);',
                partition_name || '_object_idx',
                partition_name
            );
        END LOOP;

        week_start := week_end;
    END LOOP;
END $$;
